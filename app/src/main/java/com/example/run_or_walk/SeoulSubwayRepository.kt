package com.example.run_or_walk

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZonedDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle
import java.util.Locale

/** IDs and names from TOPIS OA-12764's 2026-09-02 station catalog, without invented coordinates. */
data class OfficialSubwayStation(
    val stopId: String,
    val routeId: String,
    val name: String,
    val lineName: String,
    val point: MapPoint? = null
)

data class OfficialSubwayArrival(
    val identity: OfficialBoardingIdentity,
    val directionLabel: String,
    val trainLabel: String,
    val trainId: String,
    val snapshot: RealtimeArrivalSnapshot,
    val statusMessage: String = "",
    val gatewayFetchedAtEpochMillis: Long? = null,
    /** Provider text is display-only; it never establishes a safe countdown. */
    val officialArrivalMessage: String = "",
    val sourceReceivedAtEpochMillis: Long? = null,
    val gatewayContract: SubwayGatewayContract = SubwayGatewayContract.UNKNOWN,
    val issues: Set<SubwayArrivalIssue> = emptySet()
)

enum class SubwayGatewayContract { RAW_FIELDS, LEGACY_ALIASES, UNKNOWN }
enum class SubwayArrivalIssue {
    LEGACY_GATEWAY_FIELDS, SOURCE_TIMESTAMP_MISSING, SOURCE_FUTURE, DEVICE_CLOCK_BEHIND,
    SOURCE_STALE, COUNTDOWN_ELAPSED, INVALID_ETA, ETA_UNAVAILABLE, MISSING_SERVICE_IDENTITY,
    ARRIVAL_STATE_UNCERTAIN
}

enum class SeoulSubwayFailure {
    SETUP_REQUIRED, INVALID_AUTHENTICATION, REQUEST_LIMIT, SERVICE_ERROR,
    INVALID_RESPONSE, CONNECTION_FAILED, GATEWAY_ACCESS_DENIED, SERVER_WARMUP
}

/** Intentionally carries no URL, response body, key, or underlying transport exception. */
class SeoulSubwayApiException(message: String, val failure: SeoulSubwayFailure,
    val retryAfterEpochMillis: Long? = null, val serverCode: String? = null,
    val httpStatus: Int? = null) : IOException(message)

private const val SEOUL_ARRIVAL_VALIDITY_MILLIS = 60_000L
private const val SEOUL_REQUEST_INTERVAL_NANOS = 30_000_000_000L
private const val SEOUL_MAX_RESPONSE_CHARACTERS = 4_000_000

/** Local station search works before setup. Arrivals require a configured HTTPS gateway.
 * No public sample response is ever used as a production fallback.
 */
class SeoulSubwayRepository(
    context: Context,
    private val baseUrl: String = BuildConfig.SUBWAY_GATEWAY_URL,
    private val accessToken: String = BuildConfig.SUBWAY_GATEWAY_TOKEN
) {
    private val applicationContext = context.applicationContext
    private val stations: List<OfficialSubwayStation> by lazy {
        applicationContext.assets.open("seoul_subway_stations.json")
            .bufferedReader(Charsets.UTF_8).use { parseSeoulSubwayStations(it.readText()) }
    }
    private val requestMutex = Mutex()
    private val requestBackoff = WorkerRequestBackoff()
    // Keyed by the provider's request name: selecting another line at the same station reuses one call.
    private val cache = mutableMapOf<String, CachedSeoulResponse>()

    val isConfigured: Boolean get() = runCatching {
        validateSubwayGatewaySetup(baseUrl, accessToken)
    }.isSuccess

    suspend fun searchStations(query: String): List<OfficialSubwayStation> =
        withContext(Dispatchers.IO) { searchOfficialSubwayStations(stations, query) }

    suspend fun arrivals(station: OfficialSubwayStation): List<OfficialSubwayArrival> =
        withContext(Dispatchers.IO) {
            // This guard precedes catalog loading and all outbound work.
            val base = validateSubwayGatewaySetup(baseUrl, accessToken)
            if (stations.none {
                    it.stopId == station.stopId && it.routeId == station.routeId &&
                        it.name == station.name && it.lineName == station.lineName
                }) {
                throw SeoulSubwayApiException(
                    "공식 역 목록에서 탑승역과 호선을 다시 선택해 주세요.",
                    SeoulSubwayFailure.INVALID_RESPONSE
                )
            }
            requestMutex.withLock {
                requestBackoff.check(System.currentTimeMillis())
                currentCoroutineContext().ensureActive()
                val attemptAt = System.nanoTime()
                val previous = cache[station.name]
                if (previous?.failure?.retryAfterEpochMillis?.let { it > System.currentTimeMillis() } == true) {
                    throw requireNotNull(previous.failure)
                }
                if (previous != null && attemptAt - previous.attemptedAtNanos in
                    0 until SEOUL_REQUEST_INTERVAL_NANOS
                ) {
                    previous.failure?.let { throw it }
                    // Re-evaluate freshness, while preserving recptnDt from the cached source.
                    return@withLock parseWorkerSubwayArrivals(
                        requireNotNull(previous.body), station, System.currentTimeMillis()
                    )
                }
                try {
                    val body = request(base, station.name)
                    val parsed = parseWorkerSubwayArrivals(body, station, System.currentTimeMillis())
                    cache[station.name] = CachedSeoulResponse(attemptAt, body = body)
                    parsed
                } catch (cancelled: CancellationException) {
                    // A cancelled request may have reached the provider; retain the polling interval.
                    cache[station.name] = CachedSeoulResponse(attemptAt, failure = connectionFailure())
                    throw cancelled
                } catch (failure: SeoulSubwayApiException) {
                    // A failure invalidates a previous success and is throttled too.
                    requestBackoff.record(failure)
                    cache[station.name] = CachedSeoulResponse(attemptAt, failure = failure)
                    throw failure
                }
            }
        }

    private suspend fun request(base: URI, stationName: String): String {
        currentCoroutineContext().ensureActive()
        var connection: HttpURLConnection? = null
        try {
            val requestUrl = "${base.toASCIIString().trimEnd('/')}/subway" +
                "?station=${stationName.pathSegment()}"
            connection = URI(requestUrl).toURL().openConnection() as HttpURLConnection
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
                // Error pages may be HTML, or include provider diagnostics. Only an
                // allowlisted machine code is retained; raw text never leaves here.
                val body = connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readTextBounded(8_192) }
                throw parseWorkerHttpFailure(status, body, connection.getHeaderField("Retry-After"),
                    System.currentTimeMillis())
            }
            return connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val body = StringBuilder()
                val buffer = CharArray(8_192)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = reader.read(buffer)
                    if (count < 0) break
                    if (body.length + count > SEOUL_MAX_RESPONSE_CHARACTERS) throw invalidResponse()
                    body.append(buffer, 0, count)
                }
                body.toString()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: SeoulSubwayApiException) {
            throw failure
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            // Transport exceptions can embed the key-bearing request URL; never attach their cause.
            throw connectionFailure()
        } finally {
            connection?.disconnect()
        }
    }
}

private data class CachedSeoulResponse(
    val attemptedAtNanos: Long,
    val body: String? = null,
    val failure: SeoulSubwayApiException? = null
)

/** Only a configured HTTPS gateway is used by the app. The Seoul provider key stays on the server. */
internal fun validateSubwayGatewaySetup(baseUrl: String, accessToken: String): URI {
    val base = try { URI(baseUrl) } catch (_: Exception) { null }
    if (accessToken.any { it.isWhitespace() } || base == null || base.scheme != "https" ||
        base.host.isNullOrBlank() || base.rawUserInfo != null || base.rawQuery != null ||
        base.rawFragment != null || base.host.equals("swopenapi.seoul.go.kr", ignoreCase = true) ||
        (base.rawPath.isNotEmpty() && base.rawPath != "/")
    ) {
        throw SeoulSubwayApiException(
            "실시간 정보 연결 설정이 필요합니다.", SeoulSubwayFailure.SETUP_REQUIRED
        )
    }
    return base
}

internal fun parseSeoulSubwayStations(json: String): List<OfficialSubwayStation> {
    val array = try { JSONArray(json) } catch (_: JSONException) { throw invalidResponse() }
    return buildList {
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: throw invalidResponse()
            add(OfficialSubwayStation(
                stopId = item.seoulString("stopId") ?: throw invalidResponse(),
                routeId = item.seoulString("routeId") ?: throw invalidResponse(),
                name = item.seoulString("name") ?: throw invalidResponse(),
                lineName = item.seoulString("lineName") ?: throw invalidResponse()
            ))
        }
    }.distinctBy { it.routeId to it.stopId }
}

/** Returns candidates for explicit user selection; never assigns a catalog ID to a Kakao place. */
internal fun searchOfficialSubwayStations(
    stations: List<OfficialSubwayStation>, query: String
): List<OfficialSubwayStation> {
    val raw = query.trim()
    if (raw.isBlank()) return emptyList()
    // Normalize a SEARCH CANDIDATE only when the exact station exists in the catalog.
    // This never chooses a station/line identity for the user.
    fun stationText(text: String): String {
        val withoutLine = stations.asSequence().map { it.lineName }.distinct()
            .firstOrNull { text.endsWith(" $it") }?.let { text.removeSuffix(" $it") } ?: text
        return if (withoutLine.endsWith("역") && stations.any { it.name == withoutLine.dropLast(1) })
            withoutLine.dropLast(1) else withoutLine
    }
    val text = stationText(raw)
    return stations.filter { it.name.contains(text, ignoreCase = true) }
        .sortedWith(compareBy<OfficialSubwayStation> { !it.name.equals(text, ignoreCase = true) }
            .thenBy { it.name }.thenBy { it.lineName }.thenBy { it.stopId })
}

/** Workers preserves original fields plus public aliases. Alias-only legacy responses are
 * displayed as uncertain; missing vehicle/state/service identifiers never become fabricated values.
 */
internal fun parseWorkerSubwayArrivals(
    json: String, station: OfficialSubwayStation, nowEpochMillis: Long
): List<OfficialSubwayArrival> {
    val root = try { JSONObject(json) } catch (_: JSONException) { throw invalidResponse() }
    if (root.has("error")) throw parseWorkerHttpFailure(503, json, null, nowEpochMillis)
    val rows = root.optJSONArray("arrivals") ?: throw invalidResponse()
    val fetched = root.seoulString("fetchedAt")?.let {
        try { Instant.parse(it).toEpochMilli() } catch (_: Exception) { null }
    } ?: throw invalidResponse()
    return parseArrivalRows(rows, station, nowEpochMillis, fetched)
}

/** Retained direct-provider parser for fixtures/contract verification, not a network fallback. */
internal fun parseSeoulSubwayArrivals(
    json: String, station: OfficialSubwayStation, nowEpochMillis: Long
): List<OfficialSubwayArrival> {
    val root = try { JSONObject(json) } catch (_: JSONException) { throw invalidResponse() }
    val error = root.optJSONObject("errorMessage") ?: throw invalidResponse()
    when (error.seoulString("code")) {
        "INFO-200" -> return emptyList()
        "INFO-100" -> throw authenticationFailure()
        "INFO-000" -> if (error.optInt("status", -1) != 200) throw invalidResponse()
        else -> throw invalidResponse()
    }
    return parseArrivalRows(root.optJSONArray("realtimeArrivalList") ?: throw invalidResponse(),
        station, nowEpochMillis, nowEpochMillis)
}

private fun parseArrivalRows(rows: JSONArray, station: OfficialSubwayStation, now: Long,
    gatewayFetched: Long): List<OfficialSubwayArrival> {
    val arrivals = buildList {
        for (index in 0 until rows.length()) {
            val row = rows.optJSONObject(index) ?: continue
            fun text(raw: String, alias: String = raw): String? =
                row.seoulString(raw) ?: row.seoulString(alias)
            if (text("statnId", "stationId") != station.stopId ||
                text("subwayId", "lineId") != station.routeId) continue
            val direction = text("updnLine", "direction") ?: continue
            val service = text("trainLineNm", "destination") ?: continue
            val trainId = text("btrainNo").orEmpty()
            val trainType = text("btrainSttus")
            val destinationId = text("bstatnId")
            val destinationName = text("bstatnNm")
            val nextStationId = text("statnTid")
            val sourceTime = parseSeoulSourceTime(text("recptnDt", "receivedAt"))
            val rawEta = row.opt("barvlDt").takeUnless { it == null || it == JSONObject.NULL }
                ?: row.opt("arrivalSeconds")
            val seconds = rawEta?.toString()?.toDoubleOrNull()
                ?.takeIf { it.isFinite() && it >= 0.0 && it <= 86_400.0 && it % 1.0 == 0.0 }?.toLong()
            val code = text("arvlCd")
            val complete = trainId.isNotBlank() && trainType != null && destinationId != null &&
                destinationName != null && nextStationId != null
            val contract = when {
                row.has("statnId") || row.has("subwayId") || row.has("recptnDt") -> SubwayGatewayContract.RAW_FIELDS
                row.has("stationId") && row.has("lineId") && row.has("receivedAt") -> SubwayGatewayContract.LEGACY_ALIASES
                else -> SubwayGatewayContract.UNKNOWN
            }
            val arrival = if (sourceTime != null && seconds != null) sourceTime + seconds * 1_000L else 0L
            val issues = buildSet {
                if (contract == SubwayGatewayContract.LEGACY_ALIASES) add(SubwayArrivalIssue.LEGACY_GATEWAY_FIELDS)
                if (sourceTime == null) add(SubwayArrivalIssue.SOURCE_TIMESTAMP_MISSING)
                if (sourceTime != null && sourceTime > gatewayFetched) add(SubwayArrivalIssue.SOURCE_FUTURE)
                if (gatewayFetched > now && (sourceTime == null || sourceTime <= gatewayFetched))
                    add(SubwayArrivalIssue.DEVICE_CLOCK_BEHIND)
                if (sourceTime != null && now - sourceTime > SEOUL_ARRIVAL_VALIDITY_MILLIS)
                    add(SubwayArrivalIssue.SOURCE_STALE)
                if (seconds == null) add(SubwayArrivalIssue.INVALID_ETA)
                else if (seconds == 0L) add(SubwayArrivalIssue.ETA_UNAVAILABLE)
                else if (arrival <= now && sourceTime != null) add(SubwayArrivalIssue.COUNTDOWN_ELAPSED)
                if (!complete) add(SubwayArrivalIssue.MISSING_SERVICE_IDENTITY)
                if (direction !in setOf("상행", "하행", "내선", "외선") || code !in setOf("3", "4", "5", "99"))
                    add(SubwayArrivalIssue.ARRIVAL_STATE_UNCERTAIN)
            }
            val computed = when {
                sourceTime == null -> ArrivalTrustState.UNKNOWN
                sourceTime > gatewayFetched || sourceTime > now || gatewayFetched > now ->
                    ArrivalTrustState.FUTURE_TIMESTAMP
                now - sourceTime > SEOUL_ARRIVAL_VALIDITY_MILLIS -> ArrivalTrustState.STALE
                seconds == null -> ArrivalTrustState.INVALID_ETA
                seconds == 0L -> ArrivalTrustState.UNKNOWN
                arrival <= now -> ArrivalTrustState.STALE
                !complete || direction !in setOf("상행", "하행", "내선", "외선") ||
                    code !in setOf("3", "4", "5", "99") -> ArrivalTrustState.UNKNOWN
                else -> ArrivalTrustState.VALID
            }
            val serverTrust = text("trustState")?.let {
                runCatching { ArrivalTrustState.valueOf(it) }.getOrDefault(ArrivalTrustState.UNKNOWN)
            }
            // A server's VALID cannot override the phone's own clock/schema checks.
            val trust = if (computed == ArrivalTrustState.VALID && serverTrust != null) serverTrust else computed
            val identity = OfficialBoardingIdentity("seoul", station.stopId, station.routeId, direction,
                listOf(service, trainType.orEmpty(), destinationId.orEmpty(), destinationName.orEmpty(),
                    nextStationId.orEmpty()).joinToString("") { "${it.length}:$it" })
            val message = when (trust) {
                ArrivalTrustState.FUTURE_TIMESTAMP -> "정보 생성 시각 불일치 · 자동 판단 제외"
                ArrivalTrustState.STALE -> "오래된 도착정보 · 갱신 필요"
                ArrivalTrustState.INVALID_ETA -> "유효한 도착 예정시간 없음"
                ArrivalTrustState.UNKNOWN -> "방향·차량·도착 상태 확인 필요"
                ArrivalTrustState.VALID -> text("arvlMsg2", "arrivalMessage").orEmpty()
            }
            add(OfficialSubwayArrival(
                identity, "$direction · $service",
                listOfNotNull(trainType, destinationName?.let { "${it}행" }).joinToString(" · ")
                    .ifBlank { service },
                trainId,
                RealtimeArrivalSnapshot(identity, arrival,
                    fetchedAtEpochMillis = sourceTime ?: 0L,
                    validForMillis = SEOUL_ARRIVAL_VALIDITY_MILLIS,
                    reliable = trust == ArrivalTrustState.VALID, trustState = trust),
                message, gatewayFetched,
                text("arvlMsg2", "arrivalMessage").orEmpty(), sourceTime, contract, issues
            ))
        }
    }
    // Conflicting observations of one vehicle are not resolved by list order.
    val unique = arrivals.filter { it.trainId.isBlank() } + arrivals.filter { it.trainId.isNotBlank() }
        .groupBy { it.identity to it.trainId }.values.map { values ->
        val latest = values.maxBy { it.snapshot.fetchedAtEpochMillis }
        if (values.distinctBy { it.snapshot }.size > 1) latest.copy(snapshot = latest.snapshot.copy(
            reliable = false, trustState = if (values.any {
                it.snapshot.trustState == ArrivalTrustState.FUTURE_TIMESTAMP
            }) ArrivalTrustState.FUTURE_TIMESTAMP else ArrivalTrustState.UNKNOWN)) else latest
    }
    return unique.map { selected ->
        val next = if (selected.snapshot.reliable) unique.asSequence()
            .filter { it.identity == selected.identity && it.trainId.isNotBlank() &&
                it.trainId != selected.trainId && it.snapshot.reliable &&
                it.snapshot.firstArrivalAtEpochMillis > selected.snapshot.firstArrivalAtEpochMillis }
            .minByOrNull { it.snapshot.firstArrivalAtEpochMillis } else null
        val expiry = minOf(selected.snapshot.fetchedAtEpochMillis + selected.snapshot.validForMillis,
            next?.let { it.snapshot.fetchedAtEpochMillis + it.snapshot.validForMillis } ?: Long.MAX_VALUE)
        selected.copy(snapshot = selected.snapshot.copy(
            nextArrivalAtEpochMillis = next?.snapshot?.firstArrivalAtEpochMillis,
            validForMillis = expiry - selected.snapshot.fetchedAtEpochMillis))
    }.sortedWith(compareBy<OfficialSubwayArrival> { !it.snapshot.reliable }
        .thenBy { it.snapshot.firstArrivalAtEpochMillis }.thenBy { it.trainId })
}

private val seoulSourceTimeFormat = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss", Locale.KOREA)
    .withResolverStyle(ResolverStyle.STRICT)
private val seoulSourceZone = ZoneId.of("Asia/Seoul")

private fun parseSeoulSourceTime(text: String?): Long? {
    if (text == null) return null
    return try { LocalDateTime.parse(text, seoulSourceTimeFormat).atZone(seoulSourceZone)
        .toInstant().toEpochMilli() } catch (_: DateTimeParseException) { null }
        catch (_: ArithmeticException) { null }
}

private fun JSONObject.seoulString(key: String): String? =
    (opt(key) as? String)?.trim()?.takeIf(String::isNotEmpty)

private fun String.pathSegment(): String = URLEncoder.encode(this, "UTF-8").replace("+", "%20")

private fun authenticationFailure() = SeoulSubwayApiException(
    "서울시 실시간 지하철 전용 인증키와 이용 권한을 확인해 주세요.",
    SeoulSubwayFailure.INVALID_AUTHENTICATION
)

private fun invalidResponse() = SeoulSubwayApiException(
    "서울시 도착정보 응답을 확인할 수 없습니다. 잠시 후 다시 시도해 주세요.",
    SeoulSubwayFailure.INVALID_RESPONSE
)

private fun connectionFailure() = SeoulSubwayApiException(
    "서울시 도착정보 서버에 연결하지 못했습니다. 네트워크 상태를 확인해 주세요.",
    SeoulSubwayFailure.CONNECTION_FAILED
)

/** A safe diagnosis for the UI, independent from the decision engine's trust state. */
internal fun subwayArrivalDisplayStatus(arrival: OfficialSubwayArrival, now: Long): String {
    val source = arrival.sourceReceivedAtEpochMillis ?: arrival.snapshot.fetchedAtEpochMillis.takeIf { it > 0L }
    val age = source?.takeIf { it <= now }?.let { (now - it) / 1_000L }
    val reason = when {
        arrival.gatewayContract == SubwayGatewayContract.LEGACY_ALIASES ->
            "중계 서버 업데이트 필요 · 열차 번호·도착 상태 누락"
        SubwayArrivalIssue.SOURCE_FUTURE in arrival.issues ->
            "서울시 원본 생성 시각이 서버 조회 시각보다 늦어요 · 자동 판단 제외"
        SubwayArrivalIssue.DEVICE_CLOCK_BEHIND in arrival.issues ||
            arrival.gatewayFetchedAtEpochMillis?.let { it > now } == true ->
            "기기 날짜·시간이 서버보다 느려요 · 자동 날짜·시간 설정을 확인해 주세요"
        source != null && now - source > SEOUL_ARRIVAL_VALIDITY_MILLIS ->
            "서울시 원본 정보 갱신 대기 · 자동 판단 제외"
        SubwayArrivalIssue.INVALID_ETA in arrival.issues -> "도착 예정시간이 유효하지 않아요"
        SubwayArrivalIssue.ETA_UNAVAILABLE in arrival.issues -> "초 단위 도착 예정시간 미제공"
        SubwayArrivalIssue.SOURCE_TIMESTAMP_MISSING in arrival.issues -> "원본 생성 시각 미제공"
        SubwayArrivalIssue.MISSING_SERVICE_IDENTITY in arrival.issues -> "열차·운행 식별정보 부족 · 자동 판단 제외"
        SubwayArrivalIssue.COUNTDOWN_ELAPSED in arrival.issues ||
            arrival.snapshot.firstArrivalAtEpochMillis <= now -> "도착 예정시각 경과 · 다음 자동 갱신 대기"
        arrival.snapshot.trustState == ArrivalTrustState.VALID && arrival.snapshot.reliable -> "자동 판단에 사용할 수 있어요"
        else -> arrival.statusMessage.ifBlank { "도착 상태 확인 필요 · 자동 판단 제외" }
    }
    return if (age == null) reason else "$reason · 원본 생성 ${age}초 전"
}

/** A successful HTTP response with unusable rows must not be reported as a network failure. */
internal fun subwayArrivalDiagnosticSummary(arrivals: List<OfficialSubwayArrival>, now: Long): String? = when {
    arrivals.isEmpty() -> "서버 응답은 받았지만 선택한 역·호선의 도착 예정 열차가 없어요."
    arrivals.all { it.gatewayContract == SubwayGatewayContract.LEGACY_ALIASES } ->
        "도착정보 수신 완료 · 현재 중계 서버에 열차 번호·도착 상태가 빠져 있어요. Worker 업데이트 후 자동 판단을 연결할 수 있어요."
    arrivals.any { it.snapshot.reliable && it.snapshot.trustState == ArrivalTrustState.VALID &&
        now - it.snapshot.fetchedAtEpochMillis in 0L..minOf(it.snapshot.validForMillis, SEOUL_ARRIVAL_VALIDITY_MILLIS) &&
        it.snapshot.firstArrivalAtEpochMillis > now } -> null
    arrivals.all { SubwayArrivalIssue.SOURCE_FUTURE in it.issues } ->
        "도착정보 수신 완료 · 서울시 원본 생성 시각이 서버 조회보다 늦어 자동 판단에서 제외했어요."
    arrivals.any { SubwayArrivalIssue.DEVICE_CLOCK_BEHIND in it.issues ||
        it.gatewayFetchedAtEpochMillis?.let { fetched -> fetched > now } == true } ->
        "기기 날짜·시간이 서버 시각보다 느려요. 휴대전화의 날짜·시간 자동 설정을 확인해 주세요."
    arrivals.all { SubwayArrivalIssue.ETA_UNAVAILABLE in it.issues } ->
        "도착정보 수신 완료 · 서울시 원본의 도착예정시간이 모두 0초라 자동 판단을 보류했어요."
    arrivals.all { now - it.snapshot.fetchedAtEpochMillis > SEOUL_ARRIVAL_VALIDITY_MILLIS } ->
        "도착정보 수신 완료 · 서울시 원본 정보가 60초 이상 갱신되지 않아 자동 판단을 보류했어요."
    else -> "도착정보 수신 완료 · 유효한 예정시간과 열차 상태가 확인될 때까지 자동 판단을 보류해요."
}

private const val MAX_RETRY_MILLIS = 172_800_000L
private val workerErrorCodes = setOf(
    "SERVICE_NOT_CONFIGURED", "SERVER_NOT_CONFIGURED", "CLIENT_IP_UNAVAILABLE", "RATE_LIMITER_UNAVAILABLE",
    "SERVICE_UNAVAILABLE", "IP_RATE_LIMITED", "UPSTREAM_BUDGET_EXHAUSTED", "BUDGET_WARMUP",
    "UPSTREAM_REDIRECT", "UPSTREAM_REDIRECT_BLOCKED", "UPSTREAM_UNAVAILABLE", "UPSTREAM_HTTP_ERROR",
    "UPSTREAM_INVALID_JSON", "UPSTREAM_REJECTED", "UPSTREAM_TIMEOUT", "UPSTREAM_REQUEST_FAILED",
    "ARRIVAL_DATA_UNAVAILABLE", "INVALID_STATION", "NOT_FOUND", "METHOD_NOT_ALLOWED"
)

/** No untrusted body text or URL is included in the returned exception. */
internal fun parseWorkerHttpFailure(status: Int, body: String?, retryAfter: String?, now: Long): SeoulSubwayApiException {
    val root = body?.takeIf { it.length <= 8_192 }?.let { runCatching { JSONObject(it) }.getOrNull() }
    val error = root?.optJSONObject("error")
    val code = (error?.optString("code") ?: root?.optString("error"))?.takeIf { it in workerErrorCodes }
    fun validRetry(at: Long?): Long? = at?.takeIf { it > now && it - now <= MAX_RETRY_MILLIS }
    val headerRetry = retryAfter?.trim()?.let { text ->
        text.toLongOrNull()?.takeIf { it in 1L..172_800L }?.let { now + it * 1_000L }
            ?: runCatching { ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull()
    }?.let(::validRetry)
    val bodyRetry = error?.opt("retryAfterSeconds")?.toString()?.toLongOrNull()?.takeIf { it in 1L..172_800L }
        ?.let { now + it * 1_000L }
    val serverRetryAt = listOfNotNull(headerRetry, bodyRetry).maxOrNull()
        ?: if (status == 429) now + 30_000L else null
    // Legacy Workers can advertise a 24-hour bootstrap hold. Recheck their state
    // after an upgrade without shortening actual upstream/IP quota restrictions.
    val retryAt = if (code == "BUDGET_WARMUP") minOf(serverRetryAt ?: now + 30_000L,
        now + 30_000L) else serverRetryAt
    val (kind, message) = when {
        code == "BUDGET_WARMUP" -> SeoulSubwayFailure.SERVER_WARMUP to
            "서버 초기 대기 중이에요. 30초마다 상태를 다시 확인해요."
        code in setOf("SERVICE_NOT_CONFIGURED", "SERVER_NOT_CONFIGURED") -> SeoulSubwayFailure.SETUP_REQUIRED to
            "중계 서버 설정이 완료되지 않았어요. 서버의 비밀키·바인딩 설정 확인이 필요해요."
        status == 401 || status == 403 -> SeoulSubwayFailure.GATEWAY_ACCESS_DENIED to
            "중계 서버가 앱 요청을 거절했어요. Cloudflare 접근 규칙·앱 연결 설정을 확인해 주세요."
        status == 429 -> SeoulSubwayFailure.REQUEST_LIMIT to when (code) {
            "UPSTREAM_BUDGET_EXHAUSTED" -> "서울시 원본 조회 한도 보호 중이에요. 서버 안내 시각 이후 다시 확인해요."
            "IP_RATE_LIMITED" -> "이 네트워크의 요청이 잠시 제한됐어요. 잠시 후 다시 확인해요."
            else -> "실시간 도착정보 요청이 잠시 제한됐어요. 서버 안내 시각 이후 다시 확인해요."
        }
        code == "UPSTREAM_TIMEOUT" -> SeoulSubwayFailure.SERVICE_ERROR to "서울시 원본 도착정보 응답이 지연되고 있어요."
        code in setOf("UPSTREAM_INVALID_JSON", "UPSTREAM_REJECTED", "ARRIVAL_DATA_UNAVAILABLE") ->
            SeoulSubwayFailure.SERVICE_ERROR to "중계 서버는 연결됐지만 서울시 원본 도착정보를 확인할 수 없어요."
        status in 300..399 -> SeoulSubwayFailure.SERVICE_ERROR to "중계 서버 주소가 변경됐어요. 앱 연결 주소 확인이 필요해요."
        else -> SeoulSubwayFailure.SERVICE_ERROR to "실시간 정보 서버 요청을 처리하지 못했어요. 잠시 후 다시 확인해요."
    }
    return SeoulSubwayApiException(message, kind, retryAt, code, status)
}

private fun java.io.Reader.readTextBounded(limit: Int): String {
    val buffer = CharArray(limit)
    var used = 0
    while (used < limit) {
        val count = read(buffer, used, limit - used)
        if (count < 0) break
        used += count
    }
    return String(buffer, 0, used)
}

/** Shared across station names/tabs, guarded by the repository mutex. */
internal class WorkerRequestBackoff {
    private var blocked: SeoulSubwayApiException? = null
    fun record(failure: SeoulSubwayApiException) {
        if ((failure.retryAfterEpochMillis ?: Long.MIN_VALUE) >
            (blocked?.retryAfterEpochMillis ?: Long.MIN_VALUE)) blocked = failure
    }
    fun check(nowEpochMillis: Long) {
        blocked?.let {
            if ((it.retryAfterEpochMillis ?: 0L) > nowEpochMillis) throw it
            blocked = null
        }
    }
}
