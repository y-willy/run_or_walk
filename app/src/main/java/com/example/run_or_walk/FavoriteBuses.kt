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
internal data class FavoriteBusArrival(val selection: FavoriteBus?,val snapshot: RealtimeArrivalSnapshot?,val message: String)

/** One-shot visible-card queries. No polling; GG stops are shared across route cards for 30 s. */
internal class FavoriteBusArrivalLoader(
    private val bus: SeoulBusRepository = SeoulBusRepository(),
    private val gg: GyeonggiBusRepository = GyeonggiBusRepository(),
    private val lookup: GyeonggiBusLookupRepository = GyeonggiBusLookupRepository(),
    private val seoulRoutes: suspend (OfficialSeoulBusStop)->List<OfficialSeoulBusRoute> = { bus.routes(it) },
    private val seoulArrivals: suspend (OfficialSeoulBusStop,OfficialSeoulBusRoute)->List<OfficialSeoulBusArrival> = { stop,route -> bus.arrivals(stop,route) },
    private val ggVia: suspend (String)->List<GyeonggiStopRoute> = { lookup.via(it) },
    private val ggArrivals: suspend (String)->List<GyeonggiBusArrivalDisplay> = { gg.arrivals(it) }
) {
    private val mutex=Mutex()
    private val ggRows=mutableMapOf<String,Pair<Long,List<GyeonggiBusArrivalDisplay>>>()
    private val ggRoutes=mutableMapOf<String,List<GyeonggiStopRoute>>()
    suspend fun load(value: FavoriteBus): FavoriteBusArrival = mutex.withLock {
        try {
            val request=value.request
            val now=System.currentTimeMillis()
            if(request.isGyeonggi) {
                val match=requireNotNull(request.gyeonggiMatch)
                val routes=ggRoutes[match.stationId] ?: ggVia(match.stationId).also { ggRoutes[match.stationId]=it }
                val route=routes.singleOrNull { it.routeId==match.routeId && it.staOrder==match.staOrder }
                    ?: return@withLock FavoriteBusArrival(null,null,"공식 노선·방향이 변경됐어요. 정류장 찾기에서 다시 선택해 주세요.")
                if(route.routeName!=match.routeName) return@withLock FavoriteBusArrival(null,null,"공식 노선명이 변경됐어요. 다시 선택해 주세요.")
                val cached=ggRows[match.stationId]?.takeIf { now-it.first in 0..30000L }?.second
                val rows=cached ?: ggArrivals(match.stationId).also { ggRows[match.stationId]=System.currentTimeMillis() to it }
                val selected=chooseMatchingGyeonggiArrival(match,rows,System.currentTimeMillis())
                FavoriteBusArrival(value,selected?.snapshot,if(rows.isEmpty()) "현재 운행·도착정보 없음" else "선택 방향의 도착정보 없음")
            } else {
                val stop=requireNotNull(request.busStop);val saved=requireNotNull(request.busRoute)
                val route=seoulRoutes(stop).singleOrNull { it.identity==saved.identity && it.supported }
                    ?: return@withLock FavoriteBusArrival(null,null,"공식 노선·승차 순서가 변경됐어요. 다시 선택해 주세요.")
                val rows=seoulArrivals(stop,route)
                val selected=chooseMatchingBusArrival(route.identity,rows,System.currentTimeMillis())
                FavoriteBusArrival(value.copy(request=request.copy(busRoute=route)).takeIf { it.isValid() },selected?.snapshot,
                    if(rows.isEmpty()) "현재 운행·도착정보 없음" else rows.firstOrNull()?.statusMessage?.takeIf { it.isNotBlank() } ?: "유효한 도착정보 없음")
            }
        } catch(cancelled: CancellationException) { throw cancelled }
        catch(error: Exception) {
            val retry=(error as? SeoulBusApiException)?.retryAfterEpochMillis ?: (error as? GyeonggiApiException)?.retryAfterEpochMillis
            FavoriteBusArrival(null,null,when {
                retry?.let { it>System.currentTimeMillis() }==true -> "조회 제한 · 잠시 후 다시 시도해 주세요."
                (error is SeoulBusApiException && error.failure==SeoulBusFailure.ACCESS_DENIED) || (error is GyeonggiApiException && error.httpStatus in listOf(401,403)) -> "공식 API 인증을 확인해 주세요."
                else -> "도착정보 조회 실패 · 연결을 확인하고 다시 시도해 주세요."
            })
        }
    }
}
