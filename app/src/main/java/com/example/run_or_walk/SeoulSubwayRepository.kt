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
import java.time.LocalDateTime
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
    val statusMessage: String = ""
)

enum class SeoulSubwayFailure {
    SETUP_REQUIRED, INVALID_AUTHENTICATION, REQUEST_LIMIT, SERVICE_ERROR,
    INVALID_RESPONSE, CONNECTION_FAILED
}

/** Intentionally carries no URL, response body, key, or underlying transport exception. */
class SeoulSubwayApiException(message: String, val failure: SeoulSubwayFailure) : IOException(message)

private const val SEOUL_ARRIVAL_VALIDITY_MILLIS = 30_000L
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
                currentCoroutineContext().ensureActive()
                val attemptAt = System.nanoTime()
                val previous = cache[station.name]
                if (previous != null && attemptAt - previous.attemptedAtNanos in
                    0 until SEOUL_REQUEST_INTERVAL_NANOS
                ) {
                    previous.failure?.let { throw it }
                    // Re-evaluate freshness, while preserving recptnDt from the cached source.
                    return@withLock parseSeoulSubwayArrivals(
                        requireNotNull(previous.body), station, System.currentTimeMillis()
                    )
                }
                try {
                    val body = request(base, station.name)
                    val parsed = parseSeoulSubwayArrivals(body, station, System.currentTimeMillis())
                    cache[station.name] = CachedSeoulResponse(attemptAt, body = body)
                    parsed
                } catch (cancelled: CancellationException) {
                    // A cancelled request may have reached the provider; retain the polling interval.
                    cache[station.name] = CachedSeoulResponse(attemptAt, failure = connectionFailure())
                    throw cancelled
                } catch (failure: SeoulSubwayApiException) {
                    // A failure invalidates a previous success and is throttled too.
                    cache[station.name] = CachedSeoulResponse(attemptAt, failure = failure)
                    throw failure
                }
            }
        }

    private suspend fun request(base: URI, stationName: String): String {
        currentCoroutineContext().ensureActive()
        var connection: HttpURLConnection? = null
        try {
            val requestUrl = "${base.toASCIIString().trimEnd('/')}/v1/subway/arrivals" +
                "?stationName=${stationName.pathSegment()}"
            connection = URI(requestUrl).toURL().openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $accessToken")
            val status = connection.responseCode
            currentCoroutineContext().ensureActive()
            if (status !in 200..299) {
                throw when (status) {
                    401, 403 -> authenticationFailure()
                    429 -> SeoulSubwayApiException(
                        "서울시 도착정보 요청 한도에 도달했습니다. 잠시 후 다시 시도해 주세요.",
                        SeoulSubwayFailure.REQUEST_LIMIT
                    )
                    else -> SeoulSubwayApiException(
                        "서울시 도착정보 요청을 처리하지 못했습니다.", SeoulSubwayFailure.SERVICE_ERROR
                    )
                }
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
    if (accessToken.length < 32 || accessToken.any { it.isWhitespace() } || base == null || base.scheme != "https" ||
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

/** Parses the actual OA-12764 errorMessage/realtimeArrivalList contract.
 * recptnDt has no offset in the official response. Asia/Seoul is an explicit implementation
 * assumption pending operational verification; receipt time never replaces the source clock.
 * The 30-second validity is a conservative application limit, not a provider freshness guarantee.
 */
internal fun parseSeoulSubwayArrivals(
    json: String, station: OfficialSubwayStation, nowEpochMillis: Long
): List<OfficialSubwayArrival> {
    val root = try { JSONObject(json) } catch (_: JSONException) { throw invalidResponse() }
    val error = root.optJSONObject("errorMessage") ?: throw invalidResponse()
    when (error.seoulString("code")) {
        "INFO-200" -> return emptyList()
        "INFO-100" -> throw authenticationFailure()
        "INFO-000" -> if (error.optInt("status", -1) != 200) throw invalidResponse()
        null -> throw invalidResponse()
        else -> throw SeoulSubwayApiException(
            "서울시 도착정보 서비스에서 오류를 반환했습니다. 잠시 후 다시 시도해 주세요.",
            SeoulSubwayFailure.SERVICE_ERROR
        )
    }
    val rows = root.optJSONArray("realtimeArrivalList") ?: throw invalidResponse()
    val arrivals = buildList {
        for (index in 0 until rows.length()) {
            val row = rows.optJSONObject(index) ?: continue
            if (row.seoulString("statnId") != station.stopId ||
                row.seoulString("subwayId") != station.routeId
            ) continue
            val direction = row.seoulString("updnLine") ?: continue
            val trainLineName = row.seoulString("trainLineNm") ?: continue
            val trainId = row.seoulString("btrainNo") ?: continue
            val trainType = row.seoulString("btrainSttus") ?: continue
            val destinationId = row.seoulString("bstatnId") ?: continue
            val destinationName = row.seoulString("bstatnNm") ?: continue
            val sourceTime = parseSeoulSourceTime(row.seoulString("recptnDt")) ?: continue
            val seconds = row.seoulString("barvlDt")?.toLongOrNull() ?: continue
            if (seconds !in 0L..86_400L) continue
            val offset = seconds * 1_000L
            val arrivalTime = try { Math.addExact(sourceTime, offset) }
                catch (_: ArithmeticException) { continue }
            val arrivalCode = row.seoulString("arvlCd")
            val fresh = sourceTime >= 0 && sourceTime <= nowEpochMillis &&
                nowEpochMillis - sourceTime <= SEOUL_ARRIVAL_VALIDITY_MILLIS
            val reliable = fresh && seconds > 0 && arrivalTime > nowEpochMillis &&
                arrivalCode in setOf("3", "4", "5", "99")
            val identity = OfficialBoardingIdentity(
                authority = "seoul",
                stopId = station.stopId,
                routeId = station.routeId,
                directionId = direction,
                // Length-prefixing preserves boundaries even if provider text contains separators.
                // A local/express train or different destination/next-station never shares a variant.
                serviceVariant = listOf(trainLineName, trainType, destinationId, destinationName,
                    row.seoulString("statnTid").orEmpty()).joinToString("") { "${it.length}:$it" }
            )
            val status = when {
                arrivalCode == "2" -> "이미 출발한 열차 · 탑승 판단 불가"
                arrivalCode == "0" || arrivalCode == "1" -> "진입·도착 상태 · 탑승 판단 불가"
                seconds <= 0 -> "유효한 도착예정시간 없음 · 탑승 판단 불가"
                !fresh -> "도착정보 생성 시각 확인 필요 · 탑승 판단 불가"
                !reliable -> "도착정보 확인 필요 · 탑승 판단 불가"
                else -> row.seoulString("arvlMsg2").orEmpty()
            }
            add(OfficialSubwayArrival(
                identity = identity,
                directionLabel = "$direction · $trainLineName",
                trainLabel = "$trainType · ${destinationName}행",
                trainId = trainId,
                snapshot = RealtimeArrivalSnapshot(
                    identity = identity,
                    firstArrivalAtEpochMillis = arrivalTime,
                    fetchedAtEpochMillis = sourceTime,
                    validForMillis = SEOUL_ARRIVAL_VALIDITY_MILLIS,
                    reliable = reliable
                ),
                statusMessage = status
            ))
        }
    }.groupBy { it.identity to it.trainId }
        .values.map { duplicates -> duplicates.maxBy { it.snapshot.fetchedAtEpochMillis } }

    return arrivals.map { selected ->
        val next = if (selected.snapshot.reliable) arrivals.asSequence()
            .filter { it.identity == selected.identity && it.trainId != selected.trainId &&
                it.snapshot.reliable && it.snapshot.firstArrivalAtEpochMillis >
                selected.snapshot.firstArrivalAtEpochMillis }
            .minByOrNull { it.snapshot.firstArrivalAtEpochMillis } else null
        // Both records must remain valid. Never extend an older next train's lifetime.
        val expiry = minOf(
            selected.snapshot.fetchedAtEpochMillis + selected.snapshot.validForMillis,
            next?.let { it.snapshot.fetchedAtEpochMillis + it.snapshot.validForMillis } ?: Long.MAX_VALUE
        )
        selected.copy(snapshot = selected.snapshot.copy(
            nextArrivalAtEpochMillis = next?.snapshot?.firstArrivalAtEpochMillis,
            validForMillis = expiry - selected.snapshot.fetchedAtEpochMillis
        ))
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
