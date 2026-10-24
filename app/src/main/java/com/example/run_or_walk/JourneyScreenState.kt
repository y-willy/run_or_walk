package com.example.run_or_walk

import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.math.ceil

internal enum class FeatureMode { ROUTE, STOP }
internal enum class SearchField { DESTINATION, STOP }

internal data class JourneyPlace(
    val id: String,
    val name: String,
    val detail: String,
    val point: MapPoint
) {
    companion object {
        fun from(place: KakaoPlace) = JourneyPlace(
            id = "kakao-${place.id}", name = place.name,
            detail = place.address.ifBlank { "주소 정보 없음" }, point = place.point)
    }
}

internal data class JourneyTransitRoute(val id: String, val kakao: KakaoTransitChoice) {
    val durationMinutes: Int get() = ceil(kakao.totalSeconds / 60.0).toInt()
    val serviceName: String get() = kakao.serviceName
    val boardingName: String get() = kakao.boardingStopName
    val detail: String get() = listOfNotNull(
        when (kakao.firstTransitType) {
            "BUS" -> "버스"
            "SUBWAY" -> "지하철"
            else -> "대중교통"
        },
        kakao.transfers?.let { "환승 ${it}회" },
        kakao.totalWalkingSeconds?.let { "도보 약 ${ceil(it / 60.0).toInt()}분 포함" }
    ).joinToString(" · ")
}

internal class JourneySearchState {
    var query by mutableStateOf("")
    var options by mutableStateOf<List<JourneyPlace>>(emptyList())
    var selected by mutableStateOf<JourneyPlace?>(null)
    var searched by mutableStateOf(false)
}

/** One independent search/result state per tab; only the physical GPS position is shared. */
internal class JourneyScreenState(val mode: FeatureMode,
    private val alertGate: AutomaticAlertGate = AutomaticAlertGate()) {
    val destination = JourneySearchState()
    val stop = JourneySearchState()
    val scrollState = ScrollState(0)
    var routes by mutableStateOf<List<JourneyTransitRoute>>(emptyList())
    var routeSearchCompleted by mutableStateOf(false)
    var selectedRoute by mutableStateOf<JourneyTransitRoute?>(null)
    var boarding by mutableStateOf<JourneyPlace?>(null)
    var boardingSource by mutableStateOf<BoardingPointSource?>(null)
    var boardingChosenByUser by mutableStateOf(false)
    var boardingCandidates by mutableStateOf<List<JourneyPlace>>(emptyList())
    var candidatesSearched by mutableStateOf(false)
    var detailsExpanded by mutableStateOf(false)
    var changingBoarding by mutableStateOf(false)

    var stationQuery by mutableStateOf("")
    var lastArrivalPollElapsedMillis: Long? = null
    var stationOptions by mutableStateOf<List<OfficialSubwayStation>>(emptyList())
    var stationsSearched by mutableStateOf(false)
    var selectedStation by mutableStateOf<OfficialSubwayStation?>(null)
    var arrivalOptions by mutableStateOf<List<OfficialSubwayArrival>>(emptyList())
    var selectedArrival by mutableStateOf<OfficialSubwayArrival?>(null)
    var arrivalRequestFailed by mutableStateOf(false)
    var liveLoading by mutableStateOf(false)
    var liveError by mutableStateOf<String?>(null)
    var alertsEnabled by mutableStateOf(false)

    var location by mutableStateOf<GuidanceLocation?>(null)
    var guidanceWalkingRoute by mutableStateOf<GuidanceWalkingRoute?>(null)
    var walkRoute by mutableStateOf<WalkRoute?>(null)
    var walkFetchedAtEpochMillis by mutableStateOf<Long?>(null)
    var walkOrigin by mutableStateOf<MapPoint?>(null)
    var walkingPoints by mutableStateOf<List<MapPoint>>(emptyList())
    var mapCenter by mutableStateOf<MapPoint?>(null)
    var recenterRequest by mutableStateOf(0)

    var loading by mutableStateOf(false)
    var waitingForPermission by mutableStateOf(false)
    var status by mutableStateOf("")
    var error by mutableStateOf<String?>(null)
    val busy: Boolean get() = loading || waitingForPermission

    // Search, permission and live responses all carry generations. A new selection
    // invalidates even a response from an API that does not support cancellation.
    var revision: Long = 0
        private set
    var requestId: Long = 0
        private set
    var liveRequestId: Long = 0
        private set

    fun search(field: SearchField): JourneySearchState = when (field) {
        SearchField.DESTINATION -> destination
        SearchField.STOP -> stop
    }

    fun editQuery(field: SearchField, value: String) {
        search(field).apply {
            query = value
            selected = null
            options = emptyList()
            searched = false
        }
        clearRoutesAndBoarding()
        status = ""
        error = null
    }

    fun invalidateRequests() {
        revision++
        requestId++
        liveRequestId++
        loading = false
        waitingForPermission = false
        liveLoading = false
    }

    fun clearWalking() {
        invalidateRequests()
        guidanceWalkingRoute = null
        walkRoute = null
        walkFetchedAtEpochMillis = null
        walkOrigin = null
        walkingPoints = emptyList()
        location = null
    }

    fun clearOfficialSelection() {
        liveRequestId++
        selectedStation = null
        lastArrivalPollElapsedMillis = null
        selectedArrival = null
        stationOptions = emptyList()
        stationsSearched = false
        arrivalOptions = emptyList()
        arrivalRequestFailed = false
        liveError = null
        liveLoading = false
        guidanceWalkingRoute = null
    }

    fun clearRoutesAndBoarding() {
        clearWalking()
        clearOfficialSelection()
        routes = emptyList()
        routeSearchCompleted = false
        selectedRoute = null
        boarding = null
        boardingSource = null
        boardingChosenByUser = false
        boardingCandidates = emptyList()
        candidatesSearched = false
        detailsExpanded = false
        changingBoarding = false
        mapCenter = null
    }

    fun beginRequest(message: String): Long {
        requestId++
        loading = true
        error = null
        status = message
        return requestId
    }

    fun beginLiveRequest(): Long {
        liveRequestId++
        liveLoading = true
        liveError = null
        return liveRequestId
    }

    fun acceptsResponse(generation: Long): Boolean = requestId == generation
    fun acceptsLiveResponse(generation: Long): Boolean = liveRequestId == generation

    fun centerOn(point: MapPoint) {
        mapCenter = point
        recenterRequest++
    }

    /** Keep the chosen train when it disappears, but invalidate its cached live snapshot. */
    fun applyArrivalOptions(options: List<OfficialSubwayArrival>): OfficialSubwayArrival? {
        arrivalOptions = options
        val previous = selectedArrival ?: return null
        val matching = options.firstOrNull {
            it.identity == previous.identity && it.trainId == previous.trainId
        }
        arrivalRequestFailed = matching == null
        if (matching != null) selectedArrival = matching
        return matching
    }

    /** One shared gate suppresses duplicate notifications when switching tabs. */
    fun claimAutomaticAlert(status: AutoGuidanceStatus, nowEpochMillis: Long): Boolean =
        alertGate.claim(alertsEnabled, selectedArrival?.identity, selectedArrival?.trainId,
            status, nowEpochMillis)

    val boardingNotice: String?
        get() = when {
            boardingChosenByUser -> null
            boardingSource == BoardingPointSource.ESTIMATED -> "승차 위치 추정 · 지도에서 장소를 선택해 주세요."
            else -> "승차 위치 확인 필요 · 장소를 직접 선택해 주세요."
        }
}
