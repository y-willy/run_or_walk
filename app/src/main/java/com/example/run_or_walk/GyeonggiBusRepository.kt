package com.example.run_or_walk

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * Only an independently confirmed GBIS stationId can be queried here.
 * Kakao's place/stop IDs are not interchangeable with GBIS official IDs.
 * Only independently matched stop/route/order evidence can authorize guidance.
 */
internal data class GyeonggiBusArrivalDisplay(
    val stationId: String,
    val routeId: String,
    val staOrder: String,
    val routeName: String,
    val destination: String,
    val vehicleId: String,
    val seconds: Int?,
    val fetchedAtMillis: Long,
    val observedAtMillis: Long?,
    val trust: ArrivalTrustState,
    val sequence: Int,
    val etaPrecision: ArrivalTimePrecision = ArrivalTimePrecision.SECONDS,
    val sourceTimestampAvailable: Boolean = observedAtMillis != null,
    /** Fix clock-skew clamping once per response, so a screen tick cannot move the ETA. */
    val normalizedAtMillis: Long? = null,
    val serviceEnded: Boolean = false
)
internal class GyeonggiBusRepository(
    private val baseUrl: String = BuildConfig.SUBWAY_GATEWAY_URL
) {
    private val requestBackoff = GyeonggiRequestBackoff()
    val isConfigured: Boolean get() = runCatching { gatewayBase() }.isSuccess

    private fun gatewayBase(): String {
        val uri = runCatching { URI(baseUrl) }.getOrNull()
        if (uri?.scheme != "https" || uri.host.isNullOrBlank() ||
            uri.rawQuery != null || uri.rawFragment != null || uri.rawUserInfo != null ||
            uri.rawPath !in listOf("", "/") || uri.host.equals("apis.data.go.kr", true)) {
            throw IOException("경기 버스 Worker 연결 주소가 필요해요.")
        }
        return uri.toASCIIString().trimEnd('/')
    }

    suspend fun arrivals(stationId: String): List<GyeonggiBusArrivalDisplay> = withContext(Dispatchers.IO) {
        requestBackoff.check(System.currentTimeMillis())
        if (!stationId.matches(Regex("[1-9][0-9]{5,11}"))) throw IOException("공식 경기 정류소 ID를 확인해 주세요.")
        val url = gatewayBase() + "/gyeonggi/bus/arrivals?stationId=" + stationId
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.setRequestProperty("Accept", "application/json")
            val code = connection.responseCode
            currentCoroutineContext().ensureActive()
            if (code !in 200..299) throw readGyeonggiHttpFailure(connection, code).also(requestBackoff::record)
            val json = connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val value = StringBuilder()
                val chars = CharArray(4_096)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = reader.read(chars)
                    if (read < 0) break
                    if (value.length + read > 200_000) throw IOException("경기 버스 응답 크기가 너무 커요.")
                    value.append(chars, 0, read)
                }
                value.toString()
            }
            parseGyeonggiWorkerArrivals(json, stationId, System.currentTimeMillis())
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (known: IOException) { throw known }
        catch (_: Exception) { throw IOException("경기 버스 서버와 통신하지 못했어요.") }
        finally { connection.disconnect() }
    }
}
internal fun parseGyeonggiWorkerArrivals(
    payload: String, stationId: String, nowMillis: Long
): List<GyeonggiBusArrivalDisplay> {
    val root = runCatching { JSONObject(payload) }.getOrNull() ?: throw IOException("경기 응답 형식 오류")
    if (root.optString("provider") != "gyeonggi-gbis" || root.optString("stationId") != stationId) {
        throw IOException("경기 응답의 정류소가 일치하지 않아요.")
    }
    val fetchedAt = root.optString("fetchedAt").let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
        ?: throw IOException("경기 조회시각이 유효하지 않아요.")
    // GBIS may supply millisecond-resolution KST; older Workers omitted observedAt.
    val sourceObservedAt = root.optString("sourceQueryTime").takeIf(String::isNotBlank)
        ?.let { runCatching {
            LocalDateTime.parse(it.trim().replace(' ', 'T'))
                .toInstant(ZoneOffset.ofHours(9)).toEpochMilli()
        }.getOrNull() }
    val rows = root.optJSONArray("arrivals") ?: throw IOException("경기 도착정보 배열이 없어요.")
    if (rows.length() > 600) throw IOException("경기 도착정보 건수가 올바르지 않아요.")
    val routeSlots = mutableMapOf<Pair<String, String>, Int>()
    return buildList {
        for (index in 0 until rows.length()) {
            val row = rows.optJSONObject(index) ?: continue
            if (row.optString("stationId") != stationId) continue
            val routeId = row.optString("routeId").takeIf { it.matches(Regex("[1-9][0-9]{5,11}")) } ?: continue
            val rawOrder = row.optString("staOrder").takeUnless { it == "null" }.orEmpty()
            if (rawOrder.isNotBlank() && !rawOrder.matches(Regex("[1-9][0-9]{0,3}"))) continue
            val order = rawOrder
            val exactSeconds = row.opt("arrivalSeconds")?.toString()?.toDoubleOrNull()
                ?.takeIf { it.isFinite() && it in 0.0..86_400.0 && it % 1.0 == 0.0 }?.toInt()
            val providerObserved = row.optString("observedAt").takeIf(String::isNotBlank)
                ?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
                ?: sourceObservedAt
            val timedTrust = officialBusArrivalTrust(exactSeconds?.toLong(), providerObserved, fetchedAt, nowMillis)
            val serviceEnded=Regex("^(END|ENDED|CLOSED|TERMINATED)$|운행\\s*종료", RegexOption.IGNORE_CASE)
                .containsMatchIn(row.optString("status"))
            val computed = if(serviceEnded) ArrivalTrustState.UNKNOWN else timedTrust
            val routeSlot = routeId to order
            val slotIndex = (routeSlots[routeSlot] ?: 0) + 1
            routeSlots[routeSlot] = slotIndex
            val sequence = row.optInt("sequence", 0).takeIf { it in 1..2 } ?: slotIndex
            // Official minute-only ETAs are usable. A vehicle ID is optional, not a trust gate.
            // Keep an absent original timestamp absent; fetchedAt is only the ETA receipt basis.
            add(GyeonggiBusArrivalDisplay(stationId, routeId, order,
                row.optString("routeName"), row.optString("direction"),
                row.optString("vehicleId").takeIf { it.matches(Regex("[1-9][0-9]+")) }.orEmpty(),
                exactSeconds, fetchedAt, providerObserved, computed, sequence,
                if (row.optString("etaPrecision") == "minutes") ArrivalTimePrecision.MINUTES
                else ArrivalTimePrecision.SECONDS, providerObserved != null, nowMillis,serviceEnded))
        }
    }
}
