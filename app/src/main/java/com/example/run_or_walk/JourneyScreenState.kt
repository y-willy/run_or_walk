package com.example.run_or_walk

import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

internal enum class FeatureMode { ROUTE, STOP }
internal enum class SearchProvider { KAKAO, ODSAY }
internal enum class SearchField { ORIGIN, DESTINATION, STOP }

/** Only values actually returned by a place provider are shown in a candidate. */
internal data class JourneyPlace(
    val id: String,
    val name: String,
    val detail: String,
    val point: MapPoint,
    val transitStop: TransitStop? = null
) {
    companion object {
        fun from(place: KakaoPlace) = JourneyPlace(
            id = "kakao-${place.id}", name = place.name,
            detail = place.address.ifBlank { "주소 정보 없음" }, point = place.point
        )

        fun from(stop: TransitStop) = JourneyPlace(
            id = "odsay-${stop.id}", name = stop.name,
            detail = stop.line.ifBlank { "노선 정보 없음" }, point = stop.point,
            transitStop = stop
        )
    }
}

internal data class JourneyTransitRoute(
    val id: String,
    val kakao: KakaoTransitChoice? = null,
    val odsay: TransitPlan? = null
) {
    val durationMinutes: Int
        get() = kakao?.let { kotlin.math.ceil(it.totalSeconds / 60.0).toInt() }
            ?: odsay?.totalMinutes ?: 0
    val serviceName: String
        get() = kakao?.serviceName ?: odsay?.serviceName.orEmpty()
    val boardingName: String
        get() = kakao?.boardingStopName?.ifBlank { "승차 장소 정보 없음" }
            ?: odsay?.boardingStop?.name.orEmpty()
    val detail: String
        get() = kakao?.let {
            val type = when (it.firstTransitType) {
                "BUS" -> "버스"
                "SUBWAY" -> "지하철"
                else -> "대중교통"
            }
            "$type · 환승 ${it.transfers}회"
        } ?: odsay?.let {
            // TransitPlan does not expose a transfer count, so do not invent one.
            val type = when (it.boardingStop.type) {
                1 -> "지하철"
                2 -> "버스"
                else -> "대중교통"
            }
            listOf(type, it.direction.takeIf(String::isNotBlank), "ODsay 경로")
                .filterNotNull().joinToString(" · ")
        }.orEmpty()
}

internal class JourneySearchState(initialQuery: String = "") {
    var query by mutableStateOf(initialQuery)
    var options by mutableStateOf<List<JourneyPlace>>(emptyList())
    var selected by mutableStateOf<JourneyPlace?>(null)
    var searched by mutableStateOf(false)
}

/** One instance per feature AND provider. Only the GPS sensor reading is shared. */
internal class JourneyScreenState(
    val mode: FeatureMode,
    val provider: SearchProvider
) {
    val origin = JourneySearchState(if (provider == SearchProvider.KAKAO) "서울역" else "")
    val destination = JourneySearchState(if (provider == SearchProvider.KAKAO) "동탄역" else "")
    val stop = JourneySearchState(if (provider == SearchProvider.KAKAO) "서울역" else "")
    val scrollState = ScrollState(0)

    var routes by mutableStateOf<List<JourneyTransitRoute>>(emptyList())
    var routeSearchCompleted by mutableStateOf(false)
    var selectedRoute by mutableStateOf<JourneyTransitRoute?>(null)
    var boarding by mutableStateOf<JourneyPlace?>(null)
    var boardingSource by mutableStateOf<BoardingPointSource?>(null)
    var boardingChosenByUser by mutableStateOf(false)
    var boardingCandidates by mutableStateOf<List<JourneyPlace>>(emptyList())
    var candidatesSearched by mutableStateOf(false)

    var mapCenter by mutableStateOf<MapPoint?>(null)
    var recenterRequest by mutableStateOf(0)
    var walkRoute by mutableStateOf<WalkRoute?>(null)
    var walkingPoints by mutableStateOf<List<MapPoint>>(emptyList())
    var arrivalMinutes by mutableStateOf("5")
    var bufferMinutes by mutableStateOf("2")
    var assessment by mutableStateOf<UrgencyAssessment?>(null)
    var timeInputError by mutableStateOf<String?>(null)

    var loading by mutableStateOf(false)
    var waitingForPermission by mutableStateOf(false)
    var status by mutableStateOf("")
    var error by mutableStateOf<String?>(null)
    val busy: Boolean get() = loading || waitingForPermission

    // Mutations invalidate captured async work and permission requests.
    var revision: Long = 0
        private set
    var requestId: Long = 0
        private set

    fun search(field: SearchField): JourneySearchState = when (field) {
        SearchField.ORIGIN -> origin
        SearchField.DESTINATION -> destination
        SearchField.STOP -> stop
    }

    fun editQuery(field: SearchField, value: String) {
        val input = search(field)
        input.query = value
        input.selected = null
        input.options = emptyList()
        input.searched = false
        clearRoutesAndBoarding()
        status = ""
        error = null
    }

    fun clearWalking() {
        revision++
        requestId++
        loading = false
        waitingForPermission = false
        walkRoute = null
        walkingPoints = emptyList()
        assessment = null
        timeInputError = null
    }

    fun clearRoutesAndBoarding() {
        clearWalking()
        routes = emptyList()
        routeSearchCompleted = false
        selectedRoute = null
        boarding = null
        boardingSource = null
        boardingChosenByUser = false
        boardingCandidates = emptyList()
        candidatesSearched = false
        mapCenter = null
    }

    fun beginRequest(message: String): Long {
        requestId++
        loading = true
        error = null
        status = message
        return requestId
    }

    fun centerOn(point: MapPoint) {
        mapCenter = point
        recenterRequest++
    }

    val boardingNotice: String?
        get() = when {
            boardingChosenByUser -> "직접 선택한 승차 위치예요. 실제 정류장·출입구를 지도에서 확인하세요."
            boardingSource == BoardingPointSource.ESTIMATED ->
                "경로에서 추정한 승차 위치예요. 실제 정류장·출입구와 다를 수 있어요."
            boardingSource == BoardingPointSource.UNKNOWN ->
                "승차 위치 미확인 · 출발지 기준의 임시 위치예요. 아래 후보에서 정확한 장소를 선택하세요."
            else -> null
        }
}
