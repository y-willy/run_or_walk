package com.example.run_or_walk

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class SeoulBusFailure {
    SETUP_REQUIRED, ACCESS_DENIED, REQUEST_LIMIT, SERVICE_ERROR, INVALID_RESPONSE, CONNECTION_FAILED
}

/** Contains only categorized metadata, never an upstream URL, key or response body. */
class SeoulBusApiException(
    message: String,
    val failure: SeoulBusFailure,
    val retryAfterEpochMillis: Long? = null,
    val serverCode: String? = null
) : IOException(message)

private const val BUS_REQUEST_INTERVAL_NANOS = 30_000_000_000L
private const val BUS_VALIDITY_MILLIS = 60_000L
private const val BUS_MAX_RESPONSE_CHARS = 2_000_000

/** The Seoul bus service key lives only on the Worker. No bus key is compiled into the APK. */
class SeoulBusRepository(
    private val baseUrl: String = BuildConfig.SUBWAY_GATEWAY_URL,
    private val accessToken: String = BuildConfig.SUBWAY_GATEWAY_TOKEN
) {
    private val mutex = Mutex()
    private val cache = mutableMapOf<String, CachedBusResponse>()
    private val backoff = SeoulBusRequestBackoff()
    val isConfigured: Boolean get() = runCatching { validateSeoulBusGateway(baseUrl, accessToken) }.isSuccess

    suspend fun searchStops(query: String): List<OfficialSeoulBusStop> {
        val text = query.trim()
        if (text.isBlank() || text.length > 40 || text.any(Char::isISOControl)) return emptyList()
        return parseWorkerSeoulBusStops(load("/bus/stops", linkedMapOf("query" to text)))
    }

    suspend fun nearbyStops(point: MapPoint): List<OfficialSeoulBusStop> {
        if (!point.isBusCoordinate()) throw busInvalidResponse()
        return parseWorkerSeoulBusStops(load("/bus/stops", linkedMapOf("x" to point.x.busCoordinateQuery(), "y" to point.y.busCoordinateQuery())))
    }

    suspend fun routes(stop: OfficialSeoulBusStop): List<OfficialSeoulBusRoute> {
        if (!stop.stopId.isOfficialBusId() || !stop.arsId.isBusArsId()) throw busInvalidResponse()
        return parseWorkerSeoulBusRoutes(load("/bus/routes", linkedMapOf("stopId" to stop.stopId, "arsId" to stop.arsId)), stop)
    }

    suspend fun arrivals(stop: OfficialSeoulBusStop, route: OfficialSeoulBusRoute): List<OfficialSeoulBusArrival> {
        if (stop.stopId != route.stopId || !stop.stopId.isOfficialBusId() ||
            !route.routeId.isOfficialBusId() || !route.stopOrder.isBusStopOrder() || !route.identity.isComplete || !route.supported) {
            throw busInvalidResponse()
        }
        val body = load("/bus/arrivals", linkedMapOf("stopId" to stop.stopId,
            "routeId" to route.routeId, "stopOrder" to route.stopOrder))
        return parseWorkerSeoulBusArrivals(body, route, System.currentTimeMillis())
    }

    private suspend fun load(path: String, params: LinkedHashMap<String, String>): String = withContext(Dispatchers.IO) {
        val base = validateSeoulBusGateway(baseUrl, accessToken)
        val query = params.entries.joinToString("&") { "${it.key}=${it.value.busQueryValue()}" }
        val key = "$path?$query"
        mutex.withLock {
            currentCoroutineContext().ensureActive()
            backoff.check(System.currentTimeMillis())
            val attempt = System.nanoTime()
            val previous = cache[key]
            if (previous != null && attempt - previous.attemptedAtNanos in 0L until BUS_REQUEST_INTERVAL_NANOS) {
                previous.failure?.let { throw it }
                return@withLock requireNotNull(previous.body)
            }
            try {
                val body = request("${base.toASCIIString().trimEnd('/')}$key")
                cache[key] = CachedBusResponse(attempt, body)
                body
            } catch (cancelled: CancellationException) {
                cache[key] = CachedBusResponse(attempt, failure = busConnectionFailure())
                throw cancelled
            } catch (failure: SeoulBusApiException) {
                backoff.record(failure)
                cache[key] = CachedBusResponse(attempt, failure = failure)
                throw failure
            }
        }
    }

    private suspend fun request(url: String): String {
        var connection: HttpURLConnection? = null
        try {
            currentCoroutineContext().ensureActive()
            connection = URI(url).toURL().openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "PaceNudge/1.0 (Android)")
            if (accessToken.isNotBlank()) connection.setRequestProperty("Authorization", "Bearer $accessToken")
            val status = connection.responseCode
            currentCoroutineContext().ensureActive()
            if (status !in 200..299) {
                val error = connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { reader ->
                    val buffer = CharArray(8_192)
                    var used = 0
                    while (used < buffer.size) {
                        currentCoroutineContext().ensureActive()
                        val count = reader.read(buffer, used, buffer.size - used)
                        if (count < 0) break
                        used += count
                    }
                    String(buffer, 0, used)
                }
                throw parseSeoulBusHttpFailure(status, error, connection.getHeaderField("Retry-After"), System.currentTimeMillis())
            }
            return connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val result = StringBuilder()
                val buffer = CharArray(8_192)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = reader.read(buffer)
                    if (count < 0) break
                    if (result.length + count > BUS_MAX_RESPONSE_CHARS) throw busInvalidResponse()
                    result.append(buffer, 0, count)
                }
                result.toString()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: SeoulBusApiException) {
            throw failure
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            throw busConnectionFailure()
        } finally { connection?.disconnect() }
    }
}

private data class CachedBusResponse(val attemptedAtNanos: Long, val body: String? = null,
    val failure: SeoulBusApiException? = null)

internal fun validateSeoulBusGateway(baseUrl: String, accessToken: String): URI {
    val base = runCatching { URI(baseUrl) }.getOrNull()
    if (base == null || base.scheme != "https" || base.host.isNullOrBlank() ||
        base.rawUserInfo != null || base.rawQuery != null || base.rawFragment != null ||
        base.host.equals("ws.bus.go.kr", true) || base.host.equals("apis.data.go.kr", true) ||
        base.rawPath !in listOf("", "/") || accessToken.any(Char::isWhitespace)) {
        throw SeoulBusApiException("버스 실시간 중계 주소 설정이 필요해요.", SeoulBusFailure.SETUP_REQUIRED)
    }
    return base
}

internal fun parseWorkerSeoulBusStops(json: String): List<OfficialSeoulBusStop> {
    val root = busRoot(json)
    val rows = root.optJSONArray("stops") ?: throw busInvalidResponse()
    return buildList {
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            val id = row.busText("stopId")?.takeIf(String::isOfficialBusId) ?: continue
            val name = row.busText("name") ?: continue
            val x = row.busNumber("x")
            val y = row.busNumber("y")
            val point = if (x != null && y != null) MapPoint(x, y).takeIf(MapPoint::isBusCoordinate) else null
            add(OfficialSeoulBusStop(id, row.busText("arsId").orEmpty(), name, point))
        }
    }.groupBy { it.stopId }.values.mapNotNull { it.distinct().singleOrNull() }
}

internal fun parseWorkerSeoulBusRoutes(json: String, stop: OfficialSeoulBusStop): List<OfficialSeoulBusRoute> {
    val root = busRoot(json)
    if (root.busText("stopId") != stop.stopId) throw busInvalidResponse()
    val rows = root.optJSONArray("routes") ?: throw busInvalidResponse()
    return buildList {
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            val id = row.busText("routeId")?.takeIf(String::isOfficialBusId) ?: continue
            val order = row.busText("stopOrder")?.takeIf(String::isBusStopOrder) ?: continue
            val name = row.busText("name") ?: continue
            val direction = row.busText("direction").orEmpty()
            val routeType = row.busText("routeType").orEmpty()
            val supported = row.busBoolean("supported") == true && routeType in setOf("2", "3", "4", "5", "6")
            add(OfficialSeoulBusRoute(stop.stopId, id, name, order, direction, routeType, supported))
        }
    }.groupBy { it.routeId to it.stopOrder }.values.mapNotNull { it.distinct().singleOrNull() }
}

internal fun parseWorkerSeoulBusArrivals(json: String, route: OfficialSeoulBusRoute,
    nowEpochMillis: Long): List<OfficialSeoulBusArrival> {
    val root = busRoot(json)
    if (root.busText("stopId") != route.stopId || root.busText("routeId") != route.routeId ||
        root.busText("stopOrder") != route.stopOrder) throw busInvalidResponse()
    val fetchedAt = root.busInstant("fetchedAt") ?: throw busInvalidResponse()
    val rows = root.optJSONArray("arrivals") ?: throw busInvalidResponse()
    val arrivals = buildList {
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            if (row.busText("stopId") != route.stopId || row.busText("routeId") != route.routeId ||
                row.busText("stopOrder") != route.stopOrder) continue
            val observedAt = row.busInstant("observedAt")
            val seconds = row.busNumber("arrivalSeconds")?.takeIf {
                it >= 0.0 && it <= 86_400.0 && it % 1.0 == 0.0 }?.toLong()
            val full = row.busBoolean("full")
            val vehicle = row.busText("vehicleId")?.takeIf(String::isOfficialBusVehicleId).orEmpty()
            val raw = row.optJSONObject("raw")
            val vehicleState = raw?.busText("isArrive")
            val detour = raw?.busText("deTourAt")
            val routeType = row.busText("routeType")
            val issues = row.optJSONArray("issues")?.busStrings().orEmpty().toSet()
            val officialMessage = row.busText("message").orEmpty()
            val serviceStateUncertain = hasUncertainSeoulBusServiceState(officialMessage, issues)
            val arrivalAt = if (observedAt != null && seconds != null) observedAt + seconds * 1_000L else 0L
            val calculated = when {
                observedAt == null || observedAt <= 0L -> ArrivalTrustState.UNKNOWN
                observedAt > fetchedAt || observedAt > nowEpochMillis || fetchedAt > nowEpochMillis -> ArrivalTrustState.FUTURE_TIMESTAMP
                nowEpochMillis - observedAt > BUS_VALIDITY_MILLIS -> ArrivalTrustState.STALE
                seconds == null -> ArrivalTrustState.INVALID_ETA
                seconds == 0L || full != false || vehicle.isBlank() || !route.identity.isComplete || !route.supported ||
                    routeType !in setOf("2", "3", "4", "5", "6") || routeType != route.routeType ||
                    vehicleState != "0" || detour != "00" || serviceStateUncertain -> ArrivalTrustState.UNKNOWN
                arrivalAt <= nowEpochMillis -> ArrivalTrustState.STALE
                else -> ArrivalTrustState.VALID
            }
            val serverTrust = row.busText("trustState")?.let {
                runCatching { ArrivalTrustState.valueOf(it) }.getOrDefault(ArrivalTrustState.UNKNOWN)
            } ?: ArrivalTrustState.UNKNOWN
            val trust = if (calculated == ArrivalTrustState.VALID) serverTrust else calculated
            val message = when {
                full == true -> "만차 · 탑승 판단에서 제외"
                trust == ArrivalTrustState.FUTURE_TIMESTAMP -> "원본·기기 시각 불일치 · 자동 판단 제외"
                trust == ArrivalTrustState.STALE -> "원본 도착정보 갱신 대기"
                observedAt == null || observedAt <= 0L -> "원본 생성 시각 미제공 · 자동 판단 제외"
                seconds == null || seconds == 0L -> "유효한 도착 예정시간 확인 필요"
                vehicle.isBlank() -> "실제 차량 식별정보 미제공"
                full == null -> "만차 여부 확인 필요 · 자동 판단 제외"
                serviceStateUncertain -> "운행 상태 확인 필요 · 자동 판단 제외"
                trust != ArrivalTrustState.VALID -> "차량·운행 상태 확인 필요"
                else -> "실시간 도착정보 연결됨"
            }
            add(OfficialSeoulBusArrival(route.identity, vehicle, row.busText("routeName") ?: route.name,
                row.busText("direction") ?: route.direction, RealtimeArrivalSnapshot(route.identity, arrivalAt,
                    fetchedAtEpochMillis = observedAt ?: 0L, validForMillis = BUS_VALIDITY_MILLIS,
                    reliable = trust == ArrivalTrustState.VALID, trustState = trust),
                officialMessage, full, row.busBoolean("last"), message, fetchedAt, issues))
        }
    }
    val unique = arrivals.filter { it.vehicleId.isBlank() } + arrivals.filter { it.vehicleId.isNotBlank() }
        .groupBy { it.vehicleId }.values.map { observations ->
            val latest = observations.maxBy { it.snapshot.fetchedAtEpochMillis }
            if (observations.distinct().size > 1) latest.copy(snapshot = latest.snapshot.copy(
                reliable = false, trustState = if (observations.any {
                    it.snapshot.trustState == ArrivalTrustState.FUTURE_TIMESTAMP
                }) ArrivalTrustState.FUTURE_TIMESTAMP else ArrivalTrustState.UNKNOWN),
                statusMessage = "차량 정보가 서로 달라 자동 판단에서 제외했어요")
            else latest
        }
    return unique.map { first ->
        val next = if (first.isUsableBusArrival(nowEpochMillis)) unique.filter {
            it.vehicleId.isNotBlank() && it.vehicleId != first.vehicleId && it.identity == first.identity &&
                it.isUsableBusArrival(nowEpochMillis) && it.snapshot.firstArrivalAtEpochMillis > first.snapshot.firstArrivalAtEpochMillis
        }.minByOrNull { it.snapshot.firstArrivalAtEpochMillis } else null
        val expires = minOf(first.snapshot.fetchedAtEpochMillis + first.snapshot.validForMillis,
            next?.let { it.snapshot.fetchedAtEpochMillis + it.snapshot.validForMillis } ?: Long.MAX_VALUE)
        first.copy(snapshot = first.snapshot.copy(nextArrivalAtEpochMillis = next?.snapshot?.firstArrivalAtEpochMillis,
            validForMillis = expires - first.snapshot.fetchedAtEpochMillis))
    }.sortedWith(compareBy<OfficialSeoulBusArrival> { !it.snapshot.reliable }
        .thenBy { it.snapshot.firstArrivalAtEpochMillis }.thenBy { it.vehicleId })
}

internal fun parseSeoulBusHttpFailure(status: Int, body: String?, retryAfter: String?, nowEpochMillis: Long): SeoulBusApiException {
    val root = body?.takeIf { it.length <= 8_192 }?.let { runCatching { JSONObject(it) }.getOrNull() }
    val error = root?.optJSONObject("error")
    val code = error?.busText("code")?.takeIf { it in busErrorCodes }
    fun bounded(at: Long?): Long? = at?.takeIf { it > nowEpochMillis && it - nowEpochMillis <= 172_800_000L }
    val header = retryAfter?.trim()?.let { text ->
        text.toLongOrNull()?.takeIf { it in 1L..172_800L }?.let { nowEpochMillis + it * 1000L }
            ?: runCatching { ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull()
    }?.let(::bounded)
    val bodyRetry = error?.busNumber("retryAfterSeconds")?.takeIf { it in 1.0..172_800.0 && it % 1.0 == 0.0 }
        ?.let { nowEpochMillis + it.toLong() * 1000L }
    val retry = listOfNotNull(header, bodyRetry).maxOrNull() ?: if (status == 429) nowEpochMillis + 30_000L else null
    val (failure, message) = when {
        code in setOf("SEOUL_BUS_NOT_CONFIGURED", "BUS_KEY_INVALID", "BUS_SERVICE_NOT_CONFIGURED", "SERVICE_NOT_CONFIGURED") ->
            SeoulBusFailure.SETUP_REQUIRED to "서울 버스 연결 설정이 필요해요. Worker에 SEOUL_BUS_KEY Secret과 버스 바인딩을 등록해 주세요."
        code == "BUS_UPSTREAM_KEY_REJECTED" ->
            SeoulBusFailure.ACCESS_DENIED to "서울 버스 원본 서버가 인증 요청을 거절했어요(401). Worker의 SEOUL_BUS_KEY 값과 발급된 키를 확인해 주세요."
        code == "BUS_UPSTREAM_ACCESS_FORBIDDEN" ->
            SeoulBusFailure.ACCESS_DENIED to "서울 버스 원본 서버가 접근을 거절했어요(403). 승인 서비스와 API 접속 조건을 확인해 주세요."
        code == "BUS_GATEWAY_KEY_REJECTED" ->
            SeoulBusFailure.ACCESS_DENIED to "공공데이터 중계에서 서울 버스 인증키를 인식하지 못했어요. 서버에 등록된 키와 인코딩 형식을 확인해 주세요."
        code == "BUS_GATEWAY_KEY_EXPIRED" ->
            SeoulBusFailure.ACCESS_DENIED to "공공데이터 중계에서 서울 버스 인증키의 이용기간 만료를 알렸어요."
        code == "BUS_GATEWAY_ACCESS_FORBIDDEN" ->
            SeoulBusFailure.ACCESS_DENIED to "공공데이터 중계에서 서울 버스 서비스 접근을 거절했어요. 승인 범위와 접속 조건 확인이 필요해요."
        code in setOf("BUS_UPSTREAM_AUTHENTICATION", "BUS_UPSTREAM_PERMISSION") ->
            SeoulBusFailure.ACCESS_DENIED to "서울 버스 API 인증·활용 신청 권한을 확인해 주세요. 정류소정보 서비스도 승인이 필요할 수 있어요."
        status == 401 || status == 403 -> SeoulBusFailure.ACCESS_DENIED to "버스 중계 서버가 앱 요청을 거절했어요. Cloudflare 접근 설정을 확인해 주세요."
        code == "BUS_STOP_NOT_FOUND" -> SeoulBusFailure.INVALID_RESPONSE to "공식 정류소 정보를 찾지 못했어요. 정류소를 다시 선택해 주세요."
        code in setOf("BUS_ROUTE_NOT_FOUND", "BUS_SERVICE_ENDED") -> SeoulBusFailure.SERVICE_ERROR to "선택한 노선의 도착 예정 정보가 없거나 운행이 종료됐어요."
        code in setOf("BUS_STOP_MISMATCH", "BUS_ARRIVAL_MISMATCH", "BUS_STOP_AMBIGUOUS", "BUS_ROUTE_AMBIGUOUS", "BUS_ARRIVAL_AMBIGUOUS") ->
            SeoulBusFailure.INVALID_RESPONSE to "공식 정류소·노선·방향이 일치하지 않아요. 이용할 정류소와 노선을 다시 확인해 주세요."
        status == 404 -> SeoulBusFailure.SETUP_REQUIRED to "기존 중계 서버에는 버스 경로가 없어요. 새 Worker를 배포해 주세요."
        code == "BUS_UPSTREAM_RATE_LIMITED" -> SeoulBusFailure.REQUEST_LIMIT to
            "서울시 버스 원본 API의 요청 한도에 도달했어요. 서버 안내 시각 이후 자동으로 다시 확인해요."
        status == 429 -> SeoulBusFailure.REQUEST_LIMIT to "버스 도착정보 조회 한도 보호 중이에요. 서버 안내 시각 이후 자동으로 다시 확인해요."
        else -> SeoulBusFailure.SERVICE_ERROR to "서울 버스 원본·중계 서버 요청을 처리하지 못했어요. 잠시 후 다시 확인해요."
    }
    return SeoulBusApiException(message, failure, retry, code)
}

internal class SeoulBusRequestBackoff {
    private var blocked: SeoulBusApiException? = null
    fun record(failure: SeoulBusApiException) {
        if ((failure.retryAfterEpochMillis ?: Long.MIN_VALUE) > (blocked?.retryAfterEpochMillis ?: Long.MIN_VALUE)) blocked = failure
    }
    fun check(nowEpochMillis: Long) {
        blocked?.let {
            if ((it.retryAfterEpochMillis ?: 0L) > nowEpochMillis) throw it
            blocked = null
        }
    }
}

private val busErrorCodes = setOf("SEOUL_BUS_NOT_CONFIGURED", "BUS_KEY_INVALID", "BUS_INVALID_QUERY", "BUS_SERVICE_UNAVAILABLE", "BUS_SERVICE_NOT_CONFIGURED", "SERVICE_NOT_CONFIGURED",
    "BUS_UPSTREAM_AUTHENTICATION", "BUS_UPSTREAM_KEY_REJECTED", "BUS_UPSTREAM_ACCESS_FORBIDDEN",
    "BUS_GATEWAY_KEY_REJECTED", "BUS_GATEWAY_KEY_EXPIRED", "BUS_GATEWAY_ACCESS_FORBIDDEN",
    "BUS_GATEWAY_RATE_LIMITED", "BUS_GATEWAY_REJECTED",
    "BUS_UPSTREAM_PERMISSION", "BUS_UPSTREAM_REJECTED", "BUS_UPSTREAM_BUDGET_EXHAUSTED", "BUS_UPSTREAM_RATE_LIMITED",
    "UPSTREAM_BUDGET_EXHAUSTED", "IP_RATE_LIMITED", "BUS_UPSTREAM_UNAVAILABLE", "BUS_UPSTREAM_TIMEOUT",
    "BUS_UPSTREAM_INVALID_JSON", "BUS_UPSTREAM_INVALID_XML", "BUS_STOP_NOT_FOUND", "BUS_ROUTE_NOT_FOUND",
    "BUS_COORDINATES_REJECTED", "BUS_REALTIME_UNAVAILABLE", "BUS_SERVICE_ENDED", "BUS_STOP_AMBIGUOUS", "BUS_ROUTE_AMBIGUOUS",
    "BUS_STOP_MISMATCH", "BUS_ARRIVAL_MISMATCH", "BUS_ARRIVAL_AMBIGUOUS", "BUS_UPSTREAM_REDIRECT",
    "INVALID_BUS_STOP", "INVALID_BUS_ROUTE", "INVALID_STOP_ORDER", "SERVICE_UNAVAILABLE", "RATE_LIMITER_UNAVAILABLE")

private fun busRoot(json: String): JSONObject = runCatching { JSONObject(json) }.getOrElse { throw busInvalidResponse() }
    .also { if (it.has("error") || it.busText("provider") != "seoul-bus" ||
        it.busInstant("fetchedAt")?.takeIf { fetched -> fetched > 0L } == null) throw busInvalidResponse() }
private fun JSONObject.busText(key: String): String? = when (val value = opt(key)) {
    is String -> value.trim().takeIf { it.isNotBlank() && it.length <= 512 && it.none(Char::isISOControl) }
    is Number -> value.toString().takeIf { it.length <= 64 }
    else -> null
}
private fun JSONObject.busNumber(key: String): Double? = opt(key).takeUnless { it == null || it == JSONObject.NULL }
    ?.toString()?.toDoubleOrNull()?.takeIf(Double::isFinite)
private fun JSONObject.busBoolean(key: String): Boolean? = opt(key) as? Boolean
private fun JSONObject.busInstant(key: String): Long? = busText(key)?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
private fun JSONArray.busStrings(): List<String> = buildList {
    for (i in 0 until length()) (opt(i) as? String)?.takeIf { it.length <= 64 && it.none(Char::isISOControl) }?.let(::add)
}
private fun String.isOfficialBusId(): Boolean = matches(Regex("[1-9][0-9]{8}"))
private fun String.isBusStopOrder(): Boolean = matches(Regex("[0-9]{1,4}")) && toIntOrNull()?.let { it > 0 } == true
private fun String.isBusArsId(): Boolean = matches(Regex("[0-9]{5}")) && any { it != '0' }
private fun MapPoint.isBusCoordinate(): Boolean = x.isFinite() && y.isFinite() && x in -180.0..180.0 && y in -90.0..90.0
private fun String.busQueryValue(): String = URLEncoder.encode(this, "UTF-8").replace("+", "%20")
private fun Double.busCoordinateQuery(): String = String.format(Locale.US, "%.8f", this)
private fun busInvalidResponse() = SeoulBusApiException("공식 버스 정류소·노선·도착정보 응답을 확인할 수 없어요.", SeoulBusFailure.INVALID_RESPONSE)
private fun busConnectionFailure() = SeoulBusApiException("버스 실시간 중계 서버에 연결하지 못했어요. 네트워크 상태를 확인해 주세요.", SeoulBusFailure.CONNECTION_FAILED)
