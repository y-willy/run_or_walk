package com.example.run_or_walk

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

import org.json.JSONArray
import org.json.JSONObject

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

// 검색된 장소
data class KakaoPlace(
    val id: String,
    val name: String,
    val address: String,
    val point: MapPoint
)

// 실제 도보 경로
data class KakaoWalkingRoute(
    val distanceMeters: Int,
    val durationSeconds: Int,
    val points: List<MapPoint>
)

// 승차 위치 출처 및 신뢰도
enum class BoardingPointSource {
    VERIFIED,  // 별도 정류장 데이터에서 검증된 위치
    ESTIMATED, // 경로 좌표 기반 추정 위치
    UNKNOWN    // 위치 미확인
}

// 대중교통 경로
data class KakaoTransitChoice(
    val totalSeconds: Int,
    val transfers: Int,
    val serviceName: String,
    val boardingStopName: String,
    val boardingPoint: MapPoint?,
    val boardingSource: BoardingPointSource = BoardingPointSource.UNKNOWN,
    val firstTransitType: String = ""
)

class KakaoApiRepository(
    private val restKey: String
) {

    // 공통 REST 요청 함수
    private fun request(
        url: String,
        params: Map<String, String>
    ): JSONObject {

        check(restKey.isNotBlank()) {
            "카카오 REST API 키가 설정되지 않았습니다."
        }

        val query = params.entries.joinToString("&") {
            val key = URLEncoder.encode(it.key, "UTF-8")
            val value = URLEncoder.encode(it.value, "UTF-8")
            "$key=$value"
        }

        val connection = URL("$url?$query")
            .openConnection() as HttpURLConnection

        connection.requestMethod = "GET"
        connection.connectTimeout = 15000
        connection.readTimeout = 15000

        // 카카오 REST 인증 헤더
        connection.setRequestProperty(
            "Authorization",
            "KakaoAK $restKey"
        )

        try {
            val status = connection.responseCode

            val stream = if (status in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }

            val body = stream?.bufferedReader()?.use {
                it.readText()
            }.orEmpty()

            if (status !in 200..299) {
                error("카카오 API 오류 ($status): $body")
            }

            return JSONObject(body)

        } finally {
            connection.disconnect()
        }
    }

    // 1. 장소 검색: 서울역, 동탄역, 버스 정류장 등
    suspend fun searchPlaces(
        query: String
    ): List<KakaoPlace> = withContext(Dispatchers.IO) {

        require(query.isNotBlank()) {
            "검색어를 입력하세요."
        }

        val json = request(
            "https://dapi.kakao.com/v2/local/search/keyword.json",
            mapOf(
                "query" to query,
                "size" to "15"
            )
        )

        val documents = json.getJSONArray("documents")

        List(documents.length()) { index ->
            val item = documents.getJSONObject(index)

            KakaoPlace(
                id = item.getString("id"),
                name = item.getString("place_name"),
                address = item.optString("address_name"),
                point = MapPoint(
                    x = item.getDouble("x"),
                    y = item.getDouble("y")
                )
            )
        }
    }

    // 2. 실제 도로망을 이용한 도보 경로
    suspend fun searchWalk(
        start: MapPoint,
        end: MapPoint
    ): KakaoWalkingRoute = withContext(Dispatchers.IO) {

        val json = request(
            "https://dapi.kakao.com/v2/routing/walk",
            mapOf(
                "start_x" to start.x.toString(),
                "start_y" to start.y.toString(),
                "end_x" to end.x.toString(),
                "end_y" to end.y.toString(),
                "route_mode" to "SHORTEST"
            )
        )

        val status = json.optString("status")

        if (status != "OK") {
            error("도보 경로 검색 실패: $status")
        }

        val route = json.getJSONObject("route")
        val info = route.getJSONObject("properties")
        val legs = route.getJSONArray("legs")

        // 지도에 표시할 경로 좌표
        val points = mutableListOf<MapPoint>()

        for (i in 0 until legs.length()) {
            val leg = legs.getJSONObject(i)
            val steps = leg.getJSONArray("steps")

            for (j in 0 until steps.length()) {
                val step = steps.getJSONObject(j)

                val path = step.optJSONObject("path")
                    ?.optJSONArray("points")
                    ?: continue

                for (k in 0 until path.length()) {
                    val pair = path.getJSONArray(k)

                    // 응답: [경도, 위도]
                    points.add(
                        MapPoint(
                            x = pair.getDouble(0),
                            y = pair.getDouble(1)
                        )
                    )
                }
            }
        }

        KakaoWalkingRoute(
            distanceMeters = info.getInt("totalDistance"),
            durationSeconds = info.getInt("totalTime"),
            points = points
        )
    }

    // 3. 출발지 → 목적지 대중교통 경로
    suspend fun searchPublicTransit(
        start: MapPoint,
        end: MapPoint
    ): List<KakaoTransitChoice> =
        withContext(Dispatchers.IO) {

            val json = request(
                "https://dapi.kakao.com/v2/routing/publictraffic",
                mapOf(
                    "start_x" to start.x.toString(),
                    "start_y" to start.y.toString(),
                    "end_x" to end.x.toString(),
                    "end_y" to end.y.toString()
                )
            )

            val status = json.optString("status")

            if (status != "OK") {
                error("대중교통 경로 검색 실패: $status")
            }

            val routes = json.optJSONArray("routes")
                ?: return@withContext emptyList()

            val results = mutableListOf<KakaoTransitChoice>()

            for (i in 0 until routes.length()) {

                val route = routes.getJSONObject(i)
                val info = route.getJSONObject("properties")
                val steps = route.getJSONArray("steps")

                // 첫 버스 또는 지하철 탑승 단계 찾기
                var firstTransit: JSONObject? = null

                for (j in 0 until steps.length()) {
                    val step = steps.getJSONObject(j)

                    val type = step
                        .getJSONObject("properties")
                        .optString("type")

                    if (type == "BUS" || type == "SUBWAY") {
                        firstTransit = step
                        break
                    }
                }

                val boarding = firstTransit ?: continue
                val boardingInfo = boarding.getJSONObject("properties")
                val transitType = boardingInfo.optString("type")

                val serviceName = boardingInfo
                    .optJSONArray("vehicles")
                    ?.optJSONObject(0)
                    ?.optString("name")
                    .orEmpty()

                val firstStopObj = boardingInfo
                    .optJSONArray("stops")
                    ?.optJSONObject(0)

                val stopName = firstStopObj
                    ?.optString("name")
                    .orEmpty()

                // 카카오 대중교통 경로의 첫 좌표는 경로 상 추정 좌표(ESTIMATED)로 처리합니다.
                val firstPoint = boarding
                    .optJSONObject("path")
                    ?.optJSONArray("points")
                    ?.optJSONArray(0)

                val (boardingPoint, boardingSource) = if (
                    firstPoint != null &&
                    firstPoint.length() >= 2
                ) {
                    MapPoint(
                        x = firstPoint.getDouble(0),
                        y = firstPoint.getDouble(1)
                    ) to BoardingPointSource.ESTIMATED
                } else {
                    null to BoardingPointSource.UNKNOWN
                }

                results.add(
                    KakaoTransitChoice(
                        totalSeconds = info.getInt("totalTime"),
                        transfers = info.optInt("transfers"),
                        serviceName = serviceName,
                        boardingStopName = stopName,
                        boardingPoint = boardingPoint,
                        boardingSource = boardingSource,
                        firstTransitType = transitType
                    )
                )
            }

            results
        }
}