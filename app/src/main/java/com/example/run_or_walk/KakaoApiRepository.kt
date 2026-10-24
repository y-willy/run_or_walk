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
import java.net.URL
import java.net.URLEncoder

class KakaoApiException(message: String, val httpStatus: Int? = null) : IOException(message)

class KakaoApiRepository(private val restKey: String) {
    private suspend fun request(url: String, params: Map<String, String>): JSONObject {
        check(restKey.isNotBlank()) { "카카오 REST API 키가 설정되지 않았습니다." }
        currentCoroutineContext().ensureActive()
        val query = params.entries.joinToString("&") {
            "${URLEncoder.encode(it.key, "UTF-8")}=${URLEncoder.encode(it.value, "UTF-8")}"
        }
        val connection = URL("$url?$query").openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 15_000
        connection.readTimeout = 15_000
        connection.setRequestProperty("Authorization", "KakaoAK $restKey")
        try {
            val status = connection.responseCode
            currentCoroutineContext().ensureActive()
            if (status !in 200..299) {
                // Never expose response bodies or URLs containing auth/location data.
                val message = when (status) {
                    401, 403 -> "카카오 API 인증 또는 서비스 이용 권한을 확인해 주세요."
                    429 -> "카카오 API 요청 한도에 도달했습니다. 잠시 후 다시 시도해 주세요."
                    else -> "카카오 API 요청을 처리하지 못했습니다. (HTTP $status)"
                }
                throw KakaoApiException(message, status)
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            currentCoroutineContext().ensureActive()
            return JSONObject(body)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: KakaoApiException) {
            throw failure
        } catch (_: IOException) {
            currentCoroutineContext().ensureActive()
            throw KakaoApiException("카카오 서버에 연결하지 못했습니다. 네트워크 상태를 확인해 주세요.")
        } catch (_: JSONException) {
            throw KakaoApiException("카카오 API 응답을 확인할 수 없습니다. 잠시 후 다시 시도해 주세요.")
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
            ?: throw KakaoApiException("장소 검색 결과를 확인할 수 없습니다.")
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
                throw KakaoApiException("도보 경로를 받지 못했습니다. 다시 검색해 주세요.")
            }
            val route = json.optJSONObject("route")
                ?: throw KakaoApiException("도보 경로 정보를 확인할 수 없습니다.")
            val info = route.optJSONObject("properties")
                ?: throw KakaoApiException("도보 경로의 요약 정보를 확인할 수 없습니다.")
            val distance = info.nonNegativeInt("totalDistance")
                ?: throw KakaoApiException("도보 경로의 거리 정보가 없습니다.")
            val duration = info.nonNegativeInt("totalTime")
                ?: throw KakaoApiException("도보 경로의 소요시간 정보가 없습니다.")
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
            // Missing geometry is never replaced with a straight-line path.
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
                throw KakaoApiException("대중교통 경로를 받지 못했습니다. 다시 검색해 주세요.")
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
                "WALK" -> walkingDurations.add(properties.nonNegativeInt("totalTime"))
                else -> continue@routeLoop
            }
        }
        val firstBoarding = boarding ?: continue
        val boardingInfo = firstBoarding.optJSONObject("properties") ?: continue
        val vehicle = boardingInfo.optJSONArray("vehicles")?.optJSONObject(0)
        val firstStop = boardingInfo.optJSONArray("stops")?.optJSONObject(0)
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
            directionName = boardingInfo.nonBlankString("direction")
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
