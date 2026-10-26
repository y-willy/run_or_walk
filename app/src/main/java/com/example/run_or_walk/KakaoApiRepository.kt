package com.example.run_or_walk

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder

class KakaoApiRepository(private val restKey: String) {
    private suspend fun request(url: String, params: Map<String, String>): JSONObject {
        if (restKey.isBlank()) throw KakaoApiException(KakaoFailure.SETUP_REQUIRED)
        currentCoroutineContext().ensureActive()
        val query = params.entries.joinToString("&") {
            "${URLEncoder.encode(it.key, "UTF-8")}=${URLEncoder.encode(it.value, "UTF-8")}"
        }
        val connection = URL("$url?$query").openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 15_000
        connection.readTimeout = 15_000
        connection.setRequestProperty("Authorization", "KakaoAK $restKey")
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("User-Agent", "PaceNudge/1.0 (Android)")
        try {
            val status = connection.responseCode
            currentCoroutineContext().ensureActive()
            if (status !in 200..299) {
                // Never expose response bodies or URLs containing auth/location data.
                throw KakaoApiException(kakaoFailureForHttpStatus(status), status)
            }
            val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val result = StringBuilder()
                val buffer = CharArray(8_192)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = reader.read(buffer)
                    if (count < 0) break
                    if (result.length + count > 4_000_000) throw KakaoApiException(KakaoFailure.INVALID_RESPONSE)
                    result.append(buffer, 0, count)
                }
                result.toString()
            }
            currentCoroutineContext().ensureActive()
            return JSONObject(body)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: KakaoApiException) {
            throw failure
        } catch (_: SocketTimeoutException) {
            currentCoroutineContext().ensureActive()
            throw KakaoApiException(KakaoFailure.TIMEOUT)
        } catch (_: IOException) {
            currentCoroutineContext().ensureActive()
            throw KakaoApiException(KakaoFailure.NETWORK)
        } catch (_: JSONException) {
            throw KakaoApiException(KakaoFailure.INVALID_RESPONSE)
        } finally {
            connection.disconnect()
        }
    }

    suspend fun searchPlaces(query: String): List<KakaoPlace> = withContext(Dispatchers.IO) {
        require(query.isNotBlank()) { "검색어를 입력하세요." }
        val json = request(
            "https://dapi.kakao.com/v2/local/search/keyword.json",
            mapOf("query" to query, "size" to "15")
        )
        val documents = json.optJSONArray("documents")
            ?: throw KakaoApiException(KakaoFailure.INVALID_RESPONSE)
        buildList {
            for (index in 0 until documents.length()) {
                val item = documents.optJSONObject(index) ?: continue
                val point = item.coordinate() ?: continue
                val id = item.identifier("id") ?: continue
                val name = item.nonBlankString("place_name") ?: continue
                add(KakaoPlace(
                    id = id,
                    name = name,
                    address = item.nonBlankString("address_name").orEmpty(),
                    point = point,
                    categoryGroupCode = item.nonBlankString("category_group_code"),
                    categoryName = item.nonBlankString("category_name")
                ))
            }
        }
    }

    suspend fun searchStops(query: String): List<KakaoPlace> =
        searchPlaces(query).filter(KakaoPlace::isTransitStop)

    suspend fun searchWalk(start: MapPoint, end: MapPoint): KakaoWalkingRoute =
        withContext(Dispatchers.IO) {
            val json = request(
                "https://dapi.kakao.com/v2/routing/walk",
                mapOf(
                    "start_x" to start.x.toString(), "start_y" to start.y.toString(),
                    "end_x" to end.x.toString(), "end_y" to end.y.toString(),
                    "route_mode" to "SHORTEST"
                )
            )
            if (json.optString("status") != "OK") {
                throw KakaoApiException(KakaoFailure.NO_ROUTE)
            }
            val route = json.optJSONObject("route")
                ?: throw KakaoApiException(KakaoFailure.INVALID_RESPONSE)
            val info = route.optJSONObject("properties")
                ?: throw KakaoApiException(KakaoFailure.INVALID_RESPONSE)
            val distance = info.nonNegativeInt("totalDistance")
                ?: throw KakaoApiException(KakaoFailure.INVALID_RESPONSE)
            val duration = info.nonNegativeInt("totalTime")
                ?: throw KakaoApiException(KakaoFailure.INVALID_RESPONSE)
            val points = buildList {
                val legs = route.optJSONArray("legs") ?: return@buildList
                for (i in 0 until legs.length()) {
                    val steps = legs.optJSONObject(i)?.optJSONArray("steps") ?: continue
                    for (j in 0 until steps.length()) {
                        val path = steps.optJSONObject(j)?.optJSONObject("path")
                            ?.optJSONArray("points") ?: continue
                        for (k in 0 until path.length()) {
                            path.optJSONArray(k)?.coordinate()?.let(::add)
                        }
                    }
                }
            }
            // Never fabricate a straight line when the provider omitted walking geometry.
            if (points.size < 2) throw KakaoApiException(KakaoFailure.INVALID_RESPONSE)
            KakaoWalkingRoute(distance, duration, points)
        }

    suspend fun searchPublicTransit(start: MapPoint, end: MapPoint): List<KakaoTransitChoice> =
        withContext(Dispatchers.IO) {
            val json = request(
                "https://dapi.kakao.com/v2/routing/publictraffic",
                mapOf(
                    "start_x" to start.x.toString(), "start_y" to start.y.toString(),
                    "end_x" to end.x.toString(), "end_y" to end.y.toString()
                )
            )
            if (json.optString("status") != "OK") {
                throw KakaoApiException(KakaoFailure.NO_ROUTE)
            }
            selectTopTransitChoices(parseTransitChoices(json.optJSONArray("routes")))
        }
}

internal fun parseTransitChoices(routes: JSONArray?): List<KakaoTransitChoice> = buildList {
    if (routes == null) return@buildList
    routeLoop@ for (index in 0 until routes.length()) {
        val route = routes.optJSONObject(index) ?: continue
        val info = route.optJSONObject("properties") ?: continue
        val duration = info.nonNegativeInt("totalTime")?.takeIf { it > 0 } ?: continue
        val transferCount = info.nonNegativeInt("transfers")
        if (info.has("transfers") && !info.isNull("transfers") && transferCount == null) continue
        val steps = route.optJSONArray("steps") ?: continue
        var boarding: JSONObject? = null
        val walkingDurations = mutableListOf<Int?>()
        for (stepIndex in 0 until steps.length()) {
            // A malformed/unknown earlier leg could be the real first boarding.
            // Do not silently skip it and label a later vehicle as the first one.
            val step = steps.optJSONObject(stepIndex) ?: continue@routeLoop
            val properties = step.optJSONObject("properties") ?: continue@routeLoop
            when (properties.nonBlankString("type")) {
                "BUS", "SUBWAY" -> if (boarding == null) boarding = step
                "WALK", "WALKING" -> walkingDurations.add(
                    properties.nonNegativeInt("time") ?: properties.nonNegativeInt("totalTime"))
                else -> continue@routeLoop
            }
        }
        val firstBoarding = boarding ?: continue
        val boardingInfo = firstBoarding.optJSONObject("properties") ?: continue
        val vehicle = boardingInfo.optJSONArray("vehicles")?.optJSONObject(0)
        val transitStops = boardingInfo.optJSONArray("stops")
        val firstStop = transitStops?.optJSONObject(0)
        val nextListedStop = transitStops?.optJSONObject(1)
        val stopPoint = firstStop?.coordinate()
        val pathPoint = firstBoarding.optJSONObject("path")?.optJSONArray("points")
            ?.optJSONArray(0)?.coordinate()
        val point = stopPoint ?: pathPoint
        add(KakaoTransitChoice(
            totalSeconds = duration,
            transfers = transferCount,
            serviceName = vehicle?.nonBlankString("name").orEmpty(),
            boardingStopName = firstStop?.nonBlankString("name").orEmpty(),
            boardingPoint = point,
            // Provider coordinates do not verify a platform/direction or official ID.
            boardingSource = if (point == null) BoardingPointSource.UNKNOWN else BoardingPointSource.ESTIMATED,
            firstTransitType = boardingInfo.nonBlankString("type").orEmpty(),
            totalWalkingSeconds = sumKnownWalkingSeconds(walkingDurations),
            boardingStopId = firstStop?.identifier("id"),
            transitLineId = vehicle?.identifier("id"),
            directionName = boardingInfo.nonBlankString("direction"),
            nextStopName = nextListedStop?.nonBlankString("name"),
            firstLegStopNames = buildList {
                if (transitStops != null) for (i in 0 until transitStops.length())
                    transitStops.optJSONObject(i)?.nonBlankString("name")?.let(::add)
            },
            alightingStopName = transitStops?.optJSONObject(transitStops.length() - 1)?.nonBlankString("name")
        ))
    }
}

private fun JSONObject.nonBlankString(key: String): String? =
    (opt(key) as? String)?.trim()?.takeIf { it.isNotEmpty() }

private fun JSONObject.identifier(key: String): String? = when (val value = opt(key)) {
    is String -> value.trim().takeIf(String::isNotEmpty)
    is Number -> value.toString()
    else -> null
}

private fun JSONObject.nonNegativeInt(key: String): Int? {
    val number = when (val value = opt(key)) {
        is Number -> value.toDouble()
        is String -> value.toDoubleOrNull()
        else -> null
    } ?: return null
    return number.takeIf { it.isFinite() && it >= 0 && it <= Int.MAX_VALUE && it % 1.0 == 0.0 }?.toInt()
}

private fun JSONObject.coordinate(): MapPoint? = coordinate(optDouble("x"), optDouble("y"))

private fun JSONArray.coordinate(): MapPoint? =
    if (length() < 2) null else coordinate(optDouble(0), optDouble(1))

private fun coordinate(x: Double, y: Double): MapPoint? =
    if (x.isFinite() && y.isFinite() && x in -180.0..180.0 && y in -90.0..90.0) MapPoint(x, y) else null
