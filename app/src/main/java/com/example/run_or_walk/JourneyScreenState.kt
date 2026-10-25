package com.example.run_or_walk

import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.math.ceil

internal enum class FeatureMode { ROUTE, STOP }
internal enum class SearchField { DEPARTURE, DESTINATION, STOP }

internal data class JourneyPlace(
    val id: String,
    val name: String,
    val detail: String,
    val point: MapPoint,
    val categoryGroupCode: String? = null,
    val categoryName: String? = null
) {
    val isSubwayStation: Boolean
        get() = categoryGroupCode == "SW8" || categoryName?.split('>')
            ?.map(String::trim)?.any { it in setOf("지하철역", "전철역", "지하철역,전철역") } == true

    val isBusStop: Boolean
        get() = categoryName?.split('>')?.map(String::trim)?.any { it == "버스정류장" } == true

    companion object {
        fun from(place: KakaoPlace) = JourneyPlace(
            id = "kakao-${place.id}", name = place.name,
            detail = place.address.ifBlank { "주소 정보 없음" }, point = place.point,
            categoryGroupCode = place.categoryGroupCode, categoryName = place.categoryName)
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
    val departure = JourneySearchState()
    val destination = JourneySearchState()
    val stop = JourneySearchState()
    val scrollState = ScrollState(0)
    var routes by mutableStateOf<List<JourneyTransitRoute>>(emptyList())
    var routeSearchCompleted by mutableStateOf(false)
    var selectedRoute by mutableStateOf<JourneyTransitRoute?>(null)
    var boarding by mutableStateOf<JourneyPlace?>(null)
    var boardingSource by mutableStateOf<BoardingPointSource?>(null)
    var boardingChosenByUser by mutableStateOf(false)
    var boardingMatchedAutomatically by mutableStateOf(false)
    val boardingConfirmed: Boolean get() = boardingChosenByUser || boardingMatchedAutomatically
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
    var busStopQuery by mutableStateOf("")
    var busStopOptions by mutableStateOf<List<OfficialSeoulBusStop>>(emptyList())
    var busStopsSearched by mutableStateOf(false)
    var selectedBusStop by mutableStateOf<OfficialSeoulBusStop?>(null)
    var busRouteOptions by mutableStateOf<List<OfficialSeoulBusRoute>>(emptyList())
    var selectedBusRoute by mutableStateOf<OfficialSeoulBusRoute?>(null)
    var busArrivalOptions by mutableStateOf<List<OfficialSeoulBusArrival>>(emptyList())
    var selectedBusArrival by mutableStateOf<OfficialSeoulBusArrival?>(null)
    var confirmedServiceIdentity by mutableStateOf<OfficialBoardingIdentity?>(null)
    var liveRetryAtEpochMillis by mutableStateOf<Long?>(null)
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
    val isBusJourney: Boolean get() = selectedRoute?.kakao?.firstTransitType == "BUS" ||
        boarding?.let { it.isBusStop && !it.isSubwayStation } == true

    private val activeBusArrival: OfficialSeoulBusArrival?
        get() = selectedBusArrival?.takeIf {
            selectedBusStop?.stopId == it.identity.stopId && selectedBusRoute?.identity == it.identity
        }
    val activeArrivalIdentity: OfficialBoardingIdentity?
        get() = if (isBusJourney) activeBusArrival?.identity else selectedArrival?.identity
    val activeArrivalSnapshot: RealtimeArrivalSnapshot?
        get() = if (isBusJourney) activeBusArrival?.snapshot else selectedArrival?.snapshot
    val activeArrivalVehicleId: String?
        get() = if (isBusJourney) activeBusArrival?.vehicleId else selectedArrival?.trainId

    // Search, permission and live responses all carry generations. A new selection
    // invalidates even a response from an API that does not support cancellation.
    var revision: Long = 0
        private set
    var requestId: Long = 0
        private set
    var liveRequestId: Long = 0
        private set

    fun search(field: SearchField): JourneySearchState = when (field) {
        SearchField.DEPARTURE -> departure
        SearchField.DESTINATION -> destination
        SearchField.STOP -> stop
    }

    /** Trip routing uses a chosen origin, never a position supplied by the GPS sensor. */
    fun selectedTransitEndpoints(): Pair<MapPoint, MapPoint>? {
        val origin = departure.selected?.point ?: return null
        val destinationPoint = destination.selected?.point ?: return null
        return origin to destinationPoint
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

    /** Query edits invalidate both catalog and arrival/walking work from the old selection. */
    fun editStationQuery(value: String) {
        invalidateRequests()
        clearOfficialSelection()
        stationQuery = value
    }

    fun editBusStopQuery(value: String) {
        invalidateRequests()
        clearOfficialSelection()
        busStopQuery = value
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
        discardWalkingResults()
        location = null
    }

    /** Discard an obsolete path without throwing away a successfully observed GPS fix. */
    fun discardWalkingResults() {
        guidanceWalkingRoute = null
        walkRoute = null
        walkFetchedAtEpochMillis = null
        walkOrigin = null
        walkingPoints = emptyList()
    }

    fun clearOfficialSelection() {
        liveRequestId++
        selectedStation = null
        lastArrivalPollElapsedMillis = null
        selectedArrival = null
        confirmedServiceIdentity = null
        liveRetryAtEpochMillis = null
        stationOptions = emptyList()
        stationsSearched = false
        arrivalOptions = emptyList()
        busStopOptions = emptyList()
        busStopsSearched = false
        selectedBusStop = null
        busRouteOptions = emptyList()
        selectedBusRoute = null
        busArrivalOptions = emptyList()
        selectedBusArrival = null
        arrivalRequestFailed = false
        liveError = null
        liveLoading = false
        guidanceWalkingRoute = null
    }

    /** A new bus route keeps the chosen physical stop but invalidates its old vehicle/identity. */
    fun clearSelectedBusRoute() {
        liveRequestId++
        lastArrivalPollElapsedMillis = null
        selectedBusRoute = null
        busArrivalOptions = emptyList()
        selectedBusArrival = null
        confirmedServiceIdentity = null
        liveRetryAtEpochMillis = null
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
        boardingMatchedAutomatically = false
        boardingCandidates = emptyList()
        candidatesSearched = false
        detailsExpanded = false
        changingBoarding = false
        busStopQuery = ""
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
        // Missing vehicle IDs and conflicting duplicate rows cannot establish the same train.
        val matching = options.filter {
            previous.trainId.isNotBlank() &&
            it.identity == previous.identity && it.trainId == previous.trainId
        }.distinct().singleOrNull()
        arrivalRequestFailed = matching == null
        if (matching != null) selectedArrival = matching
        return matching
    }

    /** One shared gate suppresses duplicate notifications when switching tabs. */
    fun claimAutomaticAlert(status: AutoGuidanceStatus, nowEpochMillis: Long): Boolean =
        alertGate.claim(alertsEnabled, activeArrivalIdentity, activeArrivalVehicleId,
            status, nowEpochMillis)

    val boardingNotice: String?
        get() = when {
            boardingConfirmed -> null
            boardingSource == BoardingPointSource.ESTIMATED -> "승차 위치 추정 · 지도에서 장소를 선택해 주세요."
            else -> "승차 위치 확인 필요 · 장소를 직접 선택해 주세요."
        }
}
