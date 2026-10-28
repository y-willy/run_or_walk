package com.example.run_or_walk

import androidx.compose.runtime.*
import kotlinx.coroutines.*

internal const val WALKING_REFRESH_MILLIS=30_000L
/** Physical walking requests are independent of official ETA identity and rendering. */
internal class WalkingRefreshCadence(initialRequestElapsed:Long?=null) {
    private var lastRequestElapsed:Long?=initialRequestElapsed
    val lastRequestedElapsed:Long? get()=lastRequestElapsed
    var requesting by mutableStateOf(false);private set
    var error by mutableStateOf<KakaoApiException?>(null);private set
    private var failures=0
    private var due=0L
    private var destination:MapPoint?=null
    private var configurationBlocked=false
    fun requestDelay(elapsed:Long):Long=maxOf(1000L,maxOf(due,(lastRequestElapsed ?: elapsed)+WALKING_REFRESH_MILLIS)-elapsed)
    fun acceptExternalResult() {error=null;failures=0;configurationBlocked=false}
    fun permitConfigurationRetry() {configurationBlocked=false}
    fun tryRequest(elapsed:Long):Boolean {
        if(requesting || configurationBlocked || elapsed<due || lastRequestElapsed?.let {elapsed-it<WALKING_REFRESH_MILLIS}==true) return false
        lastRequestElapsed=elapsed;return true
    }
    suspend fun fetch(origin:MapPoint,end:MapPoint,elapsed:()->Long,epoch:()->Long,
        timeoutMillis:Long=20_000L,query:suspend (MapPoint,MapPoint)->KakaoWalkingRoute):KakaoWalkingRoute? {
        if(destination!=null && destination!=end) {
            if(requesting) return null
            lastRequestElapsed=null;failures=0;due=0;configurationBlocked=false;error=null
        }
        destination=end
        if(!tryRequest(elapsed())) return null
        requesting=true
        try {
            val result=withTimeout(timeoutMillis) {query(origin,end)}
            if(!validWalkingAccess(result,origin,end)) throw KakaoApiException(KakaoFailure.INVALID_RESPONSE)
            failures=0;due=0L;error=null
            return result
        } catch(cancelled:CancellationException) {
            if(cancelled !is TimeoutCancellationException) throw cancelled
            currentCoroutineContext().ensureActive()
            val timeout=KakaoApiException(KakaoFailure.TIMEOUT)
            record(timeout,elapsed(),epoch());throw timeout
        } catch(failure:Exception) {
            val known=failure as? KakaoApiException ?: KakaoApiException(KakaoFailure.NETWORK)
            record(known,elapsed(),epoch());throw known
        } finally {requesting=false}
    }
    private fun record(failure:KakaoApiException,elapsed:Long,epoch:Long) {
        failures++;error=failure
        configurationBlocked=failure.failure in setOf(KakaoFailure.SETUP_REQUIRED,KakaoFailure.AUTHENTICATION)
        val backoff=if(configurationBlocked) 300_000L else minOf(300_000L,30_000L*(1L shl minOf(failures-1,4)))
        due=elapsed+maxOf(backoff,(failure.retryAtEpochMillis?.minus(epoch) ?: 0L).coerceAtLeast(0L))
    }
}
internal fun walkingCalculationDue(last:Long?,now:Long)=last==null || now-last>=WALKING_REFRESH_MILLIS
internal fun walkingRequestMessage(error:KakaoApiException?):String?=error?.let {
    when(it.failure) {
        KakaoFailure.SETUP_REQUIRED,KakaoFailure.AUTHENTICATION -> "도보 경로 API 연결 설정을 확인해 주세요"
        KakaoFailure.NO_ROUTE -> "승차점까지 제공되는 도보 경로가 없어요"
        KakaoFailure.REQUEST_LIMIT -> "도보 경로 조회가 제한됐어요. 허용 시각에 다시 확인해요"
        else -> "도보 경로를 불러오지 못했어요. 자동으로 다시 확인해요"
    }
}
