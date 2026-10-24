package com.example.run_or_walk

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

// 지도 좌표: 경도(x), 위도(y)
data class MapPoint(
    val x: Double,
    val y: Double
)

// 버스 정류장 또는 지하철역
data class TransitStop(
    val id: Int,
    val name: String,
    val type: Int,
    val point: MapPoint,
    val line: String = ""
)

// 도보 경로 검색 결과
data class WalkRoute(
    val distanceMeters: Int,
    val durationSeconds: Int
)

// 대중교통 경로 검색 결과
data class TransitPlan(
    val totalMinutes: Int,
    val boardingStop: TransitStop,
    val serviceName: String,
    val direction: String
)

class TransitRepository(
    private val apiKey: String
) {
    private val baseUrl = "https://api.odsay.com/v1/api"

    // ODsay API에 GET 요청 전송
    private fun request(
        endpoint: String,
        params: Map<String, String>
    ): JSONObject {

        val allParams = params + ("apiKey" to apiKey)

        val query = allParams.entries.joinToString("&") {
            val key = URLEncoder.encode(it.key, "UTF-8")
            val value = URLEncoder.encode(it.value, "UTF-8")
            "$key=$value"
        }

        val connection = URL(
            "$baseUrl/$endpoint?$query"
        ).openConnection() as HttpURLConnection

        connection.connectTimeout = 15000
        connection.readTimeout = 15000
        connection.requestMethod = "GET"

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
                error("API 오류 ($status): $body")
            }

            val json = JSONObject(body)

            if (json.has("error")) {
                error("ODsay 오류: ${json.getJSONObject("error")}")
            }

            return json
        } finally {
            connection.disconnect()
        }
    }

    // 기능 1·2: 역 또는 정류장 이름 검색
    suspend fun searchStops(
        name: String
    ): List<TransitStop> = withContext(Dispatchers.IO) {

        require(name.length >= 2) {
            "검색어를 2글자 이상 입력하세요."
        }

        val json = request(
            "searchStation",
            mapOf(
                "stationName" to name,
                "stationClass" to "1:2:3",
                "displayCnt" to "20",
                "startNO" to "1"
            )
        )

        val stations = json.getJSONObject("result")
            .optJSONArray("station")

        if (stations == null) {
            return@withContext emptyList()
        }

        List(stations.length()) { index ->
            val item = stations.getJSONObject(index)

            TransitStop(
                id = item.getInt("stationID"),
                name = item.getString("stationName"),
                type = item.getInt("stationClass"),
                point = MapPoint(
                    x = item.getDouble("x"),
                    y = item.getDouble("y")
                ),
                line = item.optString("laneName")
            )
        }
    }

    // 기능 1·2: 현위치에서 승차 지점까지 도보 경로 계산
    suspend fun searchWalkRoute(
        start: MapPoint,
        end: MapPoint
    ): WalkRoute = withContext(Dispatchers.IO) {

        val coordinates = "${start.x},${start.y}," +
                "${end.x},${end.y}"

        val json = request(
            "searchWalkPathV2",
            mapOf(
                "opt" to "reco",
                "loc" to coordinates
            )
        )

        val path = json.getJSONObject("result")
            .getJSONArray("path")
            .getJSONObject(0)

        if (!path.optBoolean("hasPathResult")) {
            error("도보 경로를 찾지 못했습니다.")
        }

        val summary = path.getJSONObject("recommend")
            .getJSONObject("summary")

        WalkRoute(
            distanceMeters = summary.getInt("distance"),
            durationSeconds = summary.getInt("duration")
        )
    }

    // 기능 1: 출발역 → 도착역 대중교통 경로 검색
    suspend fun searchTransitRoutes(
        start: TransitStop,
        end: TransitStop
    ): List<TransitPlan> = withContext(Dispatchers.IO) {

        val json = request(
            "searchPubTransPathT",
            mapOf(
                "SX" to start.point.x.toString(),
                "SY" to start.point.y.toString(),
                "EX" to end.point.x.toString(),
                "EY" to end.point.y.toString(),
                "SearchType" to "0" // 도시내 대중교통 경로만 검색
            )
        )

        val paths = json.getJSONObject("result")
            .optJSONArray("path")
            ?: return@withContext emptyList()

        val plans = mutableListOf<TransitPlan>()

        for (i in 0 until paths.length()) {
            val path = paths.getJSONObject(i)
            val info = path.getJSONObject("info")
            val segments = path.optJSONArray("subPath") ?: continue

            // 첫 번째 대중교통 탑승 구간 선택
            var firstTransit: JSONObject? = null

            for (j in 0 until segments.length()) {
                val segment = segments.getJSONObject(j)

                if (segment.optInt("trafficType") in 1..2) {
                    firstTransit = segment
                    break
                }
            }

            val boarding = firstTransit ?: continue
            val trafficType = boarding.getInt("trafficType")
            val lane = boarding.optJSONArray("lane")
                ?.optJSONObject(0)

            val service = when (trafficType) {
                1 -> lane?.optString("name").orEmpty()
                2 -> lane?.optString("busNo").orEmpty()
                else -> ""
            }

            plans.add(
                TransitPlan(
                    totalMinutes = info.getInt("totalTime"),
                    boardingStop = TransitStop(
                        id = boarding.getInt("startID"),
                        name = boarding.getString("startName"),
                        type = trafficType,
                        point = MapPoint(
                            x = boarding.getDouble("startX"),
                            y = boarding.getDouble("startY")
                        ),
                        line = service
                    ),
                    serviceName = service,
                    direction = boarding.optString("way")
                )
            )
        }

        plans
    }
}