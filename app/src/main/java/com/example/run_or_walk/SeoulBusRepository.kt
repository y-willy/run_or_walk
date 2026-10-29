package com.example.run_or_walk

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap
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
private const val BUS_MAX_RESPONSE_CHARS = 2_000_000

/** The Seoul bus service key lives only on the Worker. No bus key is compiled into the APK. */
class SeoulBusRepository(
    private val baseUrl: String = BuildConfig.SUBWAY_GATEWAY_URL,
    private val accessToken: String = BuildConfig.SUBWAY_GATEWAY_TOKEN,
    private val transport: (suspend (String)->String)? = null
) {
    private val mutexes = ConcurrentHashMap<String,Mutex>()
    private val permits=Semaphore(2)
    private val cache = ConcurrentHashMap<String, CachedBusResponse>()
    private val backoff = SeoulBusRequestBackoff()
    private val routeInfoBackoff = SeoulBusRequestBackoff()
    private val sharedRateBackoff = SeoulBusRequestBackoff()
    val isConfigured: Boolean get() = runCatching { validateSeoulBusGateway(baseUrl, accessToken) }.isSuccess

    suspend fun searchStops(query: String): List<OfficialSeoulBusStop> {
        val text = normalizeSeoulBusStopQuery(query)
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

    suspend fun routeStops(routeId: String): List<OfficialSeoulBusRouteStop> {
        if (!routeId.isOfficialBusId()) throw busInvalidResponse()
        try {
            return parseWorkerSeoulBusRouteStops(load("/bus/route-stops", linkedMapOf("routeId" to routeId)),routeId)
        } catch (error: SeoulBusApiException) {
            if (error.serverCode == "BUS_UPSTREAM_KEY_REJECTED") throw SeoulBusApiException(
                "서울 버스 노선정보조회서비스 인증이 거절됐어요. Worker 키의 해당 서비스 이용 승인을 확인해 주세요.",
                error.failure, error.retryAfterEpochMillis, error.serverCode)
            throw error
        }
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
        val requestBackoff = if (path == "/bus/route-stops") routeInfoBackoff else backoff
        mutexes.getOrPut(key) {Mutex()}.withLock {
          permits.withPermit {
            currentCoroutineContext().ensureActive()
            sharedRateBackoff.check(System.currentTimeMillis())
            requestBackoff.check(System.currentTimeMillis())
            val attempt = System.nanoTime()
            val previous = cache[key]
            if (previous != null && attempt - previous.attemptedAtNanos in 0L until BUS_REQUEST_INTERVAL_NANOS) {
                previous.failure?.let { throw it }
                return@withPermit requireNotNull(previous.body)
            }
            try {
                val requestUrl="${base.toASCIIString().trimEnd('/')}$key"
                val body=transport?.invoke(requestUrl) ?: request(requestUrl)
                cache[key] = CachedBusResponse(attempt, body)
                body
            } catch (cancelled: CancellationException) {
                cache[key] = CachedBusResponse(attempt, failure = busConnectionFailure())
                throw cancelled
            } catch (failure: SeoulBusApiException) {
                if (failure.serverCode in setOf("IP_RATE_LIMITED","BUS_GATEWAY_RATE_LIMITED","RATE_LIMITER_UNAVAILABLE"))
                    sharedRateBackoff.record(failure) else requestBackoff.record(failure)
                cache[key] = CachedBusResponse(attempt, failure = failure)
                throw failure
            }
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

internal fun normalizeSeoulBusStopQuery(query: String): String {
    val trimmed = query.trim()
    val ars = trimmed.replace(Regex("[\\s-]"), "")
    return if (ars.matches(Regex("[0-9]{5}"))) ars else trimmed
        .removeSuffix("버스정류장").removeSuffix("정류장").removeSuffix("정류소")
        .replace(Regex("\\s+"), "").removeSuffix("(중)")
        .replace(Regex("[,·ㆍ，．。]+"), ".")
        .replace("경희의료원", "경희대의료원")
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

internal fun parseWorkerSeoulBusRouteStops(json: String, routeId: String): List<OfficialSeoulBusRouteStop> {
    val root = busRoot(json)
    if (root.busText("routeId") != routeId) throw busInvalidResponse()
    val rows = root.optJSONArray("stops") ?: throw busInvalidResponse()
    if (rows.length() > 1000) throw busInvalidResponse()
    return (0 until rows.length()).mapNotNull { i ->
        val row = rows.optJSONObject(i) ?: return@mapNotNull null
        val stopId = row.busText("stopId")?.takeIf(String::isOfficialBusId) ?: return@mapNotNull null
        val order = row.busText("stopOrder")?.takeIf(String::isBusStopOrder) ?: return@mapNotNull null
        val name = row.busText("name") ?: return@mapNotNull null
        OfficialSeoulBusRouteStop(routeId,stopId,order,name)
    }.also { if (it.distinctBy { row -> row.stopOrder.toInt() }.size != it.size) throw busInvalidResponse() }
        .sortedBy { it.stopOrder.toInt() }
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
            // Older Workers classified official Seoul night type 15 as unsupported.
            // Accept that known type while preserving explicit exclusions for other types.
            val supported = (routeType == "15" || row.busBoolean("supported") != false) &&
                isSupportedSeoulBusRouteType(routeType)
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
                row.busText("stopOrder")?.let { it != route.stopOrder } == true) continue
            val observedAt = row.busInstant("observedAt")
            val seconds = row.busNumber("arrivalSeconds")?.takeIf {
                it >= 0.0 && it <= 86_400.0 && it % 1.0 == 0.0 }?.toLong()
            val full = row.busBoolean("full")
            val vehicle = row.busText("vehicleId")?.takeIf(String::isOfficialBusVehicleId).orEmpty()
            val issues = row.optJSONArray("issues")?.busStrings().orEmpty().toSet()
            val officialMessage = row.busText("message").orEmpty()
            val observed = officialBusEffectiveObservedAt(observedAt, fetchedAt, nowEpochMillis)
            val arrivalAt = seconds?.let { observed + it * 1_000L } ?: 0L
            val timingTrust = officialBusArrivalTrust(seconds, observedAt, fetchedAt, nowEpochMillis)
            val trust = if (timingTrust == ArrivalTrustState.VALID &&
                hasUncertainSeoulBusServiceState(officialMessage, issues)) ArrivalTrustState.UNKNOWN else timingTrust
            // A source flag based on missing vehicle/capacity/detour metadata does not invalidate its ETA.
            val message = when {
                full == true -> "만차 · 탑승 판단에서 제외"
                trust == ArrivalTrustState.FUTURE_TIMESTAMP -> "원본·기기 시각 불일치 · 자동 판단 제외"
                trust == ArrivalTrustState.STALE -> "공식 도착정보 갱신 대기"
                trust == ArrivalTrustState.INVALID_ETA -> "유효한 도착 예정시간 확인 필요"
                trust != ArrivalTrustState.VALID -> "공식 운행 상태 확인 필요"
                observedAt == null -> "공식 도착정보 · 조회 수신 시각 기준"
                else -> "실시간 도착정보 연결됨"
            }
            add(OfficialSeoulBusArrival(route.identity, vehicle, row.busText("routeName") ?: route.name,
                row.busText("direction") ?: route.direction, RealtimeArrivalSnapshot(route.identity, arrivalAt,
                    fetchedAtEpochMillis = observed,
                    validForMillis = officialBusArrivalExpiresAt(observedAt, fetchedAt, nowEpochMillis) - observed,
                    reliable = trust == ArrivalTrustState.VALID, trustState = trust,
                    etaPrecision = ArrivalTimePrecision.SECONDS, sourceTimestampAvailable = observedAt != null,
                    receivedAtEpochMillis = fetchedAt,
                    vehicleIdentityFallback = if (vehicle.isBlank()) "official-slot:${i + 1}" else null),
                officialMessage, full, row.busBoolean("last"), message, fetchedAt, issues, i+1, seconds))
        }
    }
    // The official slots remain distinct without IDs. A repeated actual ID only removes next-bus evidence.
    val seenVehicles = mutableSetOf<String>()
    // Official slots can be out of ETA order (e.g. a dispatch-waiting bus after an approaching bus).
    // Keep source slot identities, but choose the nearest usable official ETA first.
    val unique = arrivals.sortedBy { it.snapshot.firstArrivalAtEpochMillis }
        .filter { it.vehicleId.isBlank() || seenVehicles.add(it.vehicleId) }
    return unique.mapIndexed { firstIndex, first ->
        val next = if (first.isUsableBusArrival(nowEpochMillis)) unique.drop(firstIndex + 1).filter {
            it.identity == first.identity &&
                (first.vehicleId.isBlank() || it.vehicleId.isBlank() || it.vehicleId != first.vehicleId) &&
                it.isUsableBusArrival(nowEpochMillis) &&
                it.snapshot.firstArrivalAtEpochMillis > first.snapshot.firstArrivalAtEpochMillis
        }.minByOrNull { it.snapshot.firstArrivalAtEpochMillis } else null
        first.copy(snapshot = first.snapshot.copy(nextArrivalAtEpochMillis = next?.snapshot?.firstArrivalAtEpochMillis,
            nextArrivalPrecision = next?.snapshot?.etaPrecision,
            nextArrivalValidUntilEpochMillis = next?.snapshot?.let { it.fetchedAtEpochMillis + it.validForMillis }))
    }
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
    @Synchronized fun record(failure: SeoulBusApiException) {
        if ((failure.retryAfterEpochMillis ?: Long.MIN_VALUE) > (blocked?.retryAfterEpochMillis ?: Long.MIN_VALUE)) blocked = failure
    }
    @Synchronized fun check(nowEpochMillis: Long) {
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
