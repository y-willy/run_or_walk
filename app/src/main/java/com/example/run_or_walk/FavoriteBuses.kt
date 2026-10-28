package com.example.run_or_walk

import android.content.Context
import androidx.compose.runtime.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/** Static official selection only: the existing codec rejects mixed providers and invalid IDs. */
internal data class FavoriteBus(val request: JourneyMonitoringRequest,val alias: String="") {
    val key: String get()=with(request.identity) { "$authority:$stopId:$routeId:$directionId" }
    val routeName: String get()=request.busRoute?.name ?: request.gyeonggiMatch?.routeName.orEmpty()
    val direction: String get()=request.busRoute?.direction ?: request.gyeonggiMatch?.destination.orEmpty()
    fun isValid()=request.isBus && request.isValid() && alias.length<=80 && alias.none(Char::isISOControl)
}
internal object FavoriteBusCodec {
    fun encode(values: List<FavoriteBus>): String = JSONObject().put("version",1).put("items",JSONArray().apply {
        values.filter { it.isValid() }.distinctBy { it.key }.take(50).forEach { value ->
            put(JSONObject().put("alias",value.alias).put("selection",JSONObject(JourneyMonitoringRequestCodec.encode(
                value.request.copy(settings=JourneySettings(),vibrationEnabled=false)))))
        }
    }).toString()
    fun decode(raw: String?): List<FavoriteBus> = runCatching {
        if(raw==null || raw.length>300000) return emptyList()
        val root=JSONObject(raw)
        if(root.optInt("version")!=1) return emptyList()
        val items=root.getJSONArray("items")
        (0 until minOf(items.length(),50)).mapNotNull { i ->
            runCatching {
                val item=items.getJSONObject(i)
                val request=JourneyMonitoringRequestCodec.decode(item.getJSONObject("selection").toString()) ?: return@runCatching null
                FavoriteBus(request,item.optString("alias")).takeIf { it.isValid() }
            }.getOrNull()
        }.distinctBy { it.key }
    }.getOrDefault(emptyList())
}
internal class FavoriteBusStore(context: Context) {
    private val prefs=context.applicationContext.getSharedPreferences("favorite_buses",Context.MODE_PRIVATE)
    var items by mutableStateOf(FavoriteBusCodec.decode(prefs.getString("items",null))); private set
    fun toggle(value: FavoriteBus) {
        if(!value.isValid()) return
        save(if(items.any { it.key==value.key }) items.filterNot { it.key==value.key } else (items+value).take(50))
    }
    fun remove(key: String)=save(items.filterNot { it.key==key })
    fun rename(key: String,label: String) { if(label.length<=80 && label.none(Char::isISOControl)) save(items.map { if(it.key==key) it.copy(alias=label.trim()) else it }) }
    fun moveUp(key: String) {
        val index=items.indexOfFirst { it.key==key }
        if(index>0) { val next=items.toMutableList();java.util.Collections.swap(next,index,index-1);save(next) }
    }
    fun moveDown(key: String) {
        val index=items.indexOfFirst { it.key==key }
        if(index in 0 until items.lastIndex) { val next=items.toMutableList();java.util.Collections.swap(next,index,index+1);save(next) }
    }
    private fun save(values: List<FavoriteBus>) { items=values;prefs.edit().putString("items",FavoriteBusCodec.encode(values)).apply() }
}

internal fun restoreFavoriteBus(screen: JourneyScreenState,value: FavoriteBus) {
    require(value.isValid())
    val request=value.request
    screen.clearRoutesAndBoarding()
    screen.destination.selected=null;screen.destination.query="";screen.destination.options=emptyList()
    screen.boarding=JourneyPlace("favorite-${value.key}",request.boardingName,"공식 버스 승차 정류장",request.boardingPoint,categoryName="버스정류장")
    screen.stop.selected=screen.boarding;screen.stop.query=request.boardingName
    screen.boardingChosenByUser=true;screen.boardingSource=BoardingPointSource.VERIFIED
    screen.selectedBusStop=request.busStop;screen.selectedBusRoute=request.busRoute
    screen.busStopOptions=listOfNotNull(request.busStop);screen.busRouteOptions=listOfNotNull(request.busRoute)
    screen.selectedGyeonggiMatch=request.gyeonggiMatch;screen.gyeonggiStationId=request.gyeonggiMatch?.stationId.orEmpty()
    screen.confirmedServiceIdentity=request.identity
    screen.status="저장한 공식 정류장·방향으로 도착정보를 조회해요."
}
internal enum class FavoriteBusFailure { TRANSIENT, RATE_LIMIT, CONFIGURATION }
internal data class FavoriteBusArrival(val selection: FavoriteBus?,val snapshot: RealtimeArrivalSnapshot?,val message: String,
    val retryAtEpochMillis: Long?=null,val failed: Boolean=false,val failureType:FavoriteBusFailure?=null)

/** Visible-card polling shares provider requests, preserves original observations, and obeys server backoff. */
internal class FavoriteBusArrivalLoader(
    private val bus: SeoulBusRepository = SeoulBusRepository(),
    private val gg: GyeonggiBusRepository = GyeonggiBusRepository(),
    private val lookup: GyeonggiBusLookupRepository = GyeonggiBusLookupRepository(),
    private val seoulRoutes: suspend (OfficialSeoulBusStop)->List<OfficialSeoulBusRoute> = { bus.routes(it) },
    private val seoulArrivals: suspend (OfficialSeoulBusStop,OfficialSeoulBusRoute)->List<OfficialSeoulBusArrival> = { stop,route -> bus.arrivals(stop,route) },
    private val ggVia: suspend (String)->List<GyeonggiStopRoute> = { lookup.via(it) },
    private val ggArrivals: suspend (String)->List<GyeonggiBusArrivalDisplay> = { gg.arrivals(it) },
    private val clock: ()->Long = System::currentTimeMillis
) {
    private data class Entry(var due:Long=0,var result:FavoriteBusArrival?=null,
        var seoul:List<OfficialSeoulBusArrival> = emptyList(),var gyeonggi:List<GyeonggiBusArrivalDisplay> = emptyList())
    private val mutex=Mutex()
    private val entries=mutableMapOf<String,Entry>()
    private val ggRows=mutableMapOf<String,Pair<Long,List<GyeonggiBusArrivalDisplay>>>()
    private val ggRoutes=mutableMapOf<String,Pair<Long,List<GyeonggiStopRoute>>>()
    private val seoulRouteRows=mutableMapOf<String,Pair<Long,List<OfficialSeoulBusRoute>>>()
    private val providerFailures=mutableMapOf<String,Pair<Int,FavoriteBusArrival>>()
    private fun current(value:FavoriteBus,entry:Entry,now:Long):FavoriteBusArrival {
        val previous=entry.result ?: return FavoriteBusArrival(null,null,"첫 도착정보를 연결하고 있어요")
        val snapshot=if(previous.selection==null) null else if(value.request.isGyeonggi)
            chooseMatchingGyeonggiArrival(requireNotNull(value.request.gyeonggiMatch),entry.gyeonggi,now)?.snapshot
            else chooseMatchingBusArrival(value.request.identity,entry.seoul,now)?.snapshot
        return previous.copy(snapshot=snapshot)
    }
    suspend fun load(value: FavoriteBus): FavoriteBusArrival = mutex.withLock {
        if(!value.isValid()) return@withLock FavoriteBusArrival(null,null,"공식 승차정보를 다시 선택해 주세요.")
        val request=value.request
        val entry=entries.getOrPut(value.key) { Entry() }
        val now=clock()
        if(entry.due>now) return@withLock current(value,entry,now)
        val blocked=providerFailures[request.identity.authority]?.second
        if(blocked?.retryAtEpochMillis?.let {it>now}==true) {
            entry.due=requireNotNull(blocked.retryAtEpochMillis)
            entry.result=blocked.copy(selection=entry.result?.selection)
            return@withLock current(value,entry,now)
        }
        entry.due=now+30000L
        try {
            val selected:FavoriteBus
            if(request.isGyeonggi) {
                val match=requireNotNull(request.gyeonggiMatch)
                val routes=ggRoutes[match.stationId]?.takeIf { now-it.first in 0L until 30000L }?.second
                    ?: ggVia(match.stationId).also { ggRoutes[match.stationId]=clock() to it }
                val route=routes.singleOrNull { it.routeId==match.routeId && it.staOrder==match.staOrder }
                if(route==null || route.routeName!=match.routeName) {
                    entry.gyeonggi=emptyList();entry.result=FavoriteBusArrival(null,null,"공식 노선·방향이 변경됐어요. 다시 선택해 주세요.")
                    return@withLock requireNotNull(entry.result)
                }
                val rows=ggRows[match.stationId]?.takeIf { now-it.first in 0L until 30000L }?.second
                    ?: ggArrivals(match.stationId).also { ggRows[match.stationId]=clock() to it }
                entry.gyeonggi=mergeGyeonggiBusArrivals(match,entry.gyeonggi,rows,clock())
                selected=value
            } else {
                val stop=requireNotNull(request.busStop);val saved=requireNotNull(request.busRoute)
                val routes=seoulRouteRows[stop.stopId]?.takeIf { now-it.first in 0L until 30000L }?.second
                    ?: seoulRoutes(stop).also { seoulRouteRows[stop.stopId]=clock() to it }
                val route=routes.singleOrNull { it.identity==saved.identity && it.supported }
                if(route==null) {
                    entry.seoul=emptyList();entry.result=FavoriteBusArrival(null,null,"공식 노선·승차 순서가 변경됐어요. 다시 선택해 주세요.")
                    return@withLock requireNotNull(entry.result)
                }
                entry.seoul=mergeSeoulBusArrivals(route.identity,entry.seoul,seoulArrivals(stop,route),clock())
                selected=value.copy(request=request.copy(busRoute=route))
            }
            providerFailures.remove(request.identity.authority)
            entry.result=FavoriteBusArrival(selected,null,if(entry.seoul.isEmpty() && entry.gyeonggi.isEmpty()) "운행정보 없음" else "새로운 차량 도착정보를 기다리고 있어요")
        } catch(cancelled: CancellationException) { entry.due=clock()+30000L;throw cancelled }
        catch(error: Exception) {
            val attempts=(providerFailures[request.identity.authority]?.first ?: 0)+1
            val serverRetry=(error as? SeoulBusApiException)?.retryAfterEpochMillis ?: (error as? GyeonggiApiException)?.retryAfterEpochMillis
            val configuration=error is SeoulBusApiException && error.failure in setOf(SeoulBusFailure.SETUP_REQUIRED,SeoulBusFailure.ACCESS_DENIED) ||
                error is GyeonggiApiException && (error.httpStatus in setOf(401,403,404) || error.serverCode in setOf(
                    "GYEONGGI_NOT_CONFIGURED","GYEONGGI_KEY_INVALID","GYEONGGI_KEY_REJECTED","GYEONGGI_ACCESS_FORBIDDEN",
                    "GYEONGGI_GATEWAY_KEY_REJECTED","GYEONGGI_GATEWAY_KEY_EXPIRED","GYEONGGI_GATEWAY_ACCESS_FORBIDDEN"))
            val limited=error is SeoulBusApiException && error.failure==SeoulBusFailure.REQUEST_LIMIT || error is GyeonggiApiException && error.httpStatus==429
            val category=if(configuration) FavoriteBusFailure.CONFIGURATION else if(limited) FavoriteBusFailure.RATE_LIMIT else FavoriteBusFailure.TRANSIENT
            val backoff=if(configuration) 300000L else minOf(300000L,30000L*(1L shl minOf(attempts-1,4)))
            val retry=maxOf(clock()+backoff,serverRetry ?: 0L)
            val failure=FavoriteBusArrival(null,null,when {
                configuration -> "공식 API 인증·연결 설정 확인 필요"
                limited || serverRetry?.let {it>now}==true -> "조회 제한 · 허용 시각에 자동으로 다시 확인해요."
                attempts>=5 -> "연결 상태 확인 필요 · 자동으로 다시 조회해요."
                else -> "도착정보를 불러오지 못했어요"
            },retry,true,category)
            providerFailures[request.identity.authority]=attempts to failure
            entry.due=retry
            entry.result=failure.copy(selection=entry.result?.selection)
        }
        current(value,entry,clock())
    }
}
