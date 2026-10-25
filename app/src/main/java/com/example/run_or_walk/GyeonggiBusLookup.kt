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
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Official GBIS stop/route evidence. Kakao route/stop IDs are NOT GBIS IDs. */
internal data class GyeonggiNearbyStop(
    val stationId: String, val name: String, val point: MapPoint, val distanceMeters: Double
)
internal data class GyeonggiStopRoute(
    val routeId: String, val routeName: String, val staOrder: String, val destination: String
)
internal data class GyeonggiOfficialMatch(
    val stationId: String, val routeId: String, val staOrder: String,
    val routeName: String, val destination: String, val point: MapPoint
) {
    val identity: OfficialBoardingIdentity get() =
        OfficialBoardingIdentity("GBIS", stationId, routeId, staOrder, routeName)
}

/** Match only one nearby physical stop; a distance-only guess cannot identify a 5100/3 bus. */
internal fun gyeonggiStopCandidates(choice: KakaoTransitChoice, stops: List<GyeonggiNearbyStop>): List<GyeonggiNearbyStop> {
    val boardingPoint = choice.boardingPoint ?: return emptyList()
    val expected = normalizedGyeonggiName(choice.boardingStopName)
    if (choice.firstTransitType != "BUS" || expected.isEmpty()) return emptyList()
    return stops.distinctBy { it.stationId }.filter {
        val name = normalizedGyeonggiName(it.name)
        val meters = gbisDistanceMeters(boardingPoint, it.point)
        val exact = name == expected
        // Provider names may append an exit/landmark. Proximity alone is never enough.
        val recognizable = name.length >= 3 && expected.length >= 3 &&
            (name.contains(expected) || expected.contains(name))
        it.point.x.isFinite() && it.point.y.isFinite() &&
            ((exact && meters <= 100.0) || (recognizable && meters <= 35.0))
    }.sortedBy { gbisDistanceMeters(boardingPoint, it.point) }
}

internal fun resolveGyeonggiBusMatch(
    choice: KakaoTransitChoice, matches: List<Pair<GyeonggiNearbyStop, GyeonggiStopRoute>>
): GyeonggiOfficialMatch? {
    if (choice.firstTransitType != "BUS") return null
    val expected = normalizedGyeonggiName(choice.serviceName)
    if (expected.isEmpty()) return null
    val same = matches.filter { (stop, route) ->
        normalizedGyeonggiName(route.routeName) == expected &&
            stop.stationId.matches(Regex("[1-9][0-9]{5,11}")) &&
            route.routeId.matches(Regex("[1-9][0-9]{5,11}")) &&
            route.staOrder.matches(Regex("[1-9][0-9]{0,3}"))
    }.distinctBy { (s,r) -> listOf(s.stationId, r.routeId, r.staOrder) }
    val selected = when {
        same.size == 1 -> same.single()
        else -> {
            // Opposite-side stops may share both name and route number.
            // Use a route-provider direction only when one official destination agrees.
            val intended = normalizedGyeonggiDestination(choice.directionName.orEmpty())
            if (intended.isBlank()) return null
            same.filter { (_, route) ->
                normalizedGyeonggiDestination(route.destination) == intended
            }.singleOrNull() ?: return null
        }
    }
    val (stop, route) = selected
    return GyeonggiOfficialMatch(stop.stationId, route.routeId, route.staOrder,
        route.routeName, route.destination, stop.point)
}

private fun normalizedGyeonggiName(name: String): String = name.trim().lowercase()
    .replace(Regex("\\s+"), "").removeSuffix("정류장").removeSuffix("정류소")

private fun normalizedGyeonggiDestination(name: String): String =
    normalizedGyeonggiName(name).removeSuffix("방향").removeSuffix("방면")
        .removeSuffix("행").removeSuffix("역")
private fun gbisDistanceMeters(a: MapPoint, b: MapPoint): Double {
    val r = 6_371_000.0
    val lat1 = Math.toRadians(a.y); val lat2 = Math.toRadians(b.y)
    val dLat = lat2 - lat1; val dLon = Math.toRadians(b.x - a.x)
    val x = sin(dLat / 2) * sin(dLat / 2) +
        cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
    return 2 * r * asin(sqrt(x.coerceIn(0.0, 1.0)))
}

/** Uses a separate optional Worker secret for the GBIS bus-station service. */
internal class GyeonggiBusLookupRepository(
    private val baseUrl: String = BuildConfig.SUBWAY_GATEWAY_URL
) {
    private fun url(path: String): String {
        val uri = runCatching { URI(baseUrl) }.getOrNull()
        if (uri?.scheme != "https" || uri.host.isNullOrBlank() ||
            uri.rawQuery != null || uri.rawFragment != null || uri.rawUserInfo != null ||
            uri.rawPath !in listOf("", "/")) throw IOException("경기버스 Worker 주소를 확인해 주세요.")
        return uri.toASCIIString().trimEnd('/') + path
    }

    private suspend fun request(path: String): JSONObject = withContext(Dispatchers.IO) {
        val connection = URI(url(path)).toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.setRequestProperty("Accept", "application/json")
            val status = connection.responseCode
            currentCoroutineContext().ensureActive()
            if (status !in 200..299) throw IOException(when (status) {
                503 -> "경기 정류소 검색 API 승인 또는 GYEONGGI_STATION_KEY Worker 설정이 필요해요."
                429 -> "경기버스 조회 한도에 도달했어요. 잠시 후 다시 시도해 주세요."
                else -> "경기 정류소 공식 검색에 실패했어요. (HTTP $status)"
            })
            val data = connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val buffer = StringBuilder()
                val chunk = CharArray(4096)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = reader.read(chunk)
                    if (count < 0) break
                    if (buffer.length + count > 200_000) throw IOException("응답 크기가 올바르지 않아요.")
                    buffer.append(chunk, 0, count)
                }
                buffer.toString()
            }
            JSONObject(data)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (known: IOException) { throw known }
        catch (_: Exception) { throw IOException("경기 정류소 Worker와 연결되지 않아요.") }
        finally { connection.disconnect() }
    }

    suspend fun nearby(point: MapPoint): List<GyeonggiNearbyStop> {
        if (!point.x.isFinite() || !point.y.isFinite()) throw IOException("승차 위치 좌표가 없어요.")
        val x = String.format(java.util.Locale.ROOT, "%.7f", point.x)
        val y = String.format(java.util.Locale.ROOT, "%.7f", point.y)
        val root = request("/gyeonggi/bus/nearby?x=$x&y=$y")
        if (root.optString("provider") != "gyeonggi-gbis" || root.optString("kind") != "nearby")
            throw IOException("경기 정류소 조회 결과를 확인할 수 없어요.")
        val array = root.optJSONArray("stops") ?: throw IOException("경기 정류소 배열이 없어요.")
        return buildList {
            for (index in 0 until array.length().coerceAtMost(100)) {
                val row = array.optJSONObject(index) ?: continue
                val id = row.optString("stationId")
                val lon = row.optDouble("x"); val lat = row.optDouble("y")
                if (id.matches(Regex("[1-9][0-9]{5,11}")) &&
                    lon.isFinite() && lat.isFinite()) add(GyeonggiNearbyStop(
                    id, row.optString("name"), MapPoint(lon, lat), row.optDouble("distanceMeters")))
            }
        }
    }

    /** Official name search backs up a 500m GPS lookup with no matching stops. */
    suspend fun searchByName(name: String): List<GyeonggiNearbyStop> {
        val query = name.trim()
        if (query.isBlank() || query.length > 40) return emptyList()
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val root = request("/gyeonggi/bus/search?query=$encoded")
        if (root.optString("provider") != "gyeonggi-gbis" || root.optString("kind") != "search")
            throw IOException("경기 정류소 이름 검색 응답을 확인할 수 없어요.")
        val array = root.optJSONArray("stops") ?: throw IOException("경기 정류소 검색 배열이 없어요.")
        return buildList {
            for (index in 0 until array.length().coerceAtMost(100)) {
                val row = array.optJSONObject(index) ?: continue
                val id = row.optString("stationId")
                val lon = row.optDouble("x"); val lat = row.optDouble("y")
                if (id.matches(Regex("[1-9][0-9]{5,11}")) &&
                    lon.isFinite() && lat.isFinite()) add(GyeonggiNearbyStop(
                    id, row.optString("name"), MapPoint(lon, lat), Double.NaN))
            }
        }
    }

    suspend fun via(stationId: String): List<GyeonggiStopRoute> {
        if (!stationId.matches(Regex("[1-9][0-9]{5,11}"))) throw IOException("경기 정류장 ID가 올바르지 않아요.")
        val root = request("/gyeonggi/bus/via?stationId=$stationId")
        if (root.optString("provider") != "gyeonggi-gbis" ||
            root.optString("kind") != "via" || root.optString("stationId") != stationId)
            throw IOException("경기 경유노선 응답이 일치하지 않아요.")
        val array = root.optJSONArray("routes") ?: throw IOException("경기 경유노선 배열이 없어요.")
        return buildList {
            for (index in 0 until array.length().coerceAtMost(100)) {
                val row = array.optJSONObject(index) ?: continue
                val routeId = row.optString("routeId")
                val order = row.optString("staOrder")
                if (routeId.matches(Regex("[1-9][0-9]{5,11}")) &&
                    order.matches(Regex("[1-9][0-9]{0,3}"))) add(GyeonggiStopRoute(
                    routeId, row.optString("routeName"), order, row.optString("destination")))
            }
        }
    }

    suspend fun match(choice: KakaoTransitChoice): GyeonggiOfficialMatch? {
        val point = choice.boardingPoint ?: return null
        val nearbyMatches = gyeonggiStopCandidates(choice, nearby(point))
        val candidates = nearbyMatches.ifEmpty {
            gyeonggiStopCandidates(choice, searchByName(choice.boardingStopName))
        }
        // Limit lookup fan-out and refuse ambiguous large candidate sets.
        if (candidates.isEmpty() || candidates.size > 3) return null
        val pairs = buildList {
            for (stop in candidates) {
                for (route in via(stop.stationId)) add(stop to route)
            }
        }
        return resolveGyeonggiBusMatch(choice, pairs)
    }
}
