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

/** Preserve official identity through the primary stop search; never reinterpret a Kakao place ID. */
internal fun OfficialSeoulBusStop.toJourneyPlace(): JourneyPlace? = point?.let {
    JourneyPlace("seoul-bus-$stopId",name,"서울 공식 정류소 · ARS $arsId",it,categoryName="버스정류장")
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
    var stopDirectionRoute by mutableStateOf<KakaoTransitChoice?>(null)
    val subwayDirectionRoute: KakaoTransitChoice? get() = selectedRoute?.kakao ?: stopDirectionRoute
    var directionMessage by mutableStateOf<String?>(null)
    var timetableAnalysis by mutableStateOf<SubwayTimetableAnalysis?>(null)
    var timetableError by mutableStateOf<String?>(null)
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
    var busDirectionMessage by mutableStateOf<String?>(null)
    var busRoutePathVerified by mutableStateOf(false)
    var selectedGyeonggiMatch by mutableStateOf<GyeonggiOfficialMatch?>(null)
    var gyeonggiStationId by mutableStateOf("")
    var gyeonggiArrivals by mutableStateOf<List<GyeonggiBusArrivalDisplay>>(emptyList())
    var gyeonggiLoading by mutableStateOf(false)
    var gyeonggiMessage by mutableStateOf<String?>(null)
    var gyeonggiGeneration: Long = 0
        private set
    fun beginGyeonggiRequest(): Long {
        gyeonggiGeneration++
        gyeonggiLoading = true
        return gyeonggiGeneration
    }
    fun acceptsGyeonggiResponse(generation: Long): Boolean = gyeonggiGeneration == generation
    fun beginSeoulBusLookup() {
        gyeonggiGeneration++
        selectedGyeonggiMatch = null
        gyeonggiArrivals = emptyList()
        gyeonggiLoading = false
        gyeonggiMessage = null
    }
    fun updateGyeonggiStationId(value: String) {
        gyeonggiGeneration++
        gyeonggiStationId = value
        gyeonggiArrivals = emptyList()
        gyeonggiMessage = null
        gyeonggiLoading = false
    }

    var confirmedServiceIdentity by mutableStateOf<OfficialBoardingIdentity?>(null)
    var lastGuidanceAssessment by mutableStateOf<AutoGuidanceAssessment?>(null)
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
    // A feeder alternative is display-only until its own realtime vehicle identity is verified.
    var accessTransitRoutes by mutableStateOf<List<JourneyTransitRoute>>(emptyList())
    var accessJourney by mutableStateOf<JourneyScreenState?>(null)
    var accessTransitMessage by mutableStateOf<String?>(null)
    var accessTransitLoading by mutableStateOf(false)
    var accessTransitFetchedAtMillis by mutableStateOf<Long?>(null)
    var accessTransitOrigin: MapPoint? = null
    var accessTransitAttemptAtMillis: Long? = null
    var accessTransitGeneration: Long = 0
        private set
    fun beginAccessTransitRequest(): Long = ++accessTransitGeneration
    var mapCenter by mutableStateOf<MapPoint?>(null)
    var mapAccessSegments by mutableStateOf<List<List<MapPoint>>>(emptyList())
    var mapAccessOrigin by mutableStateOf<MapPoint?>(null)
    var mapAccessDestination by mutableStateOf<MapPoint?>(null)
    var mapAccessMessage by mutableStateOf<String?>(null)
    var recenterRequest by mutableStateOf(0)

    var loading by mutableStateOf(false)
    var waitingForPermission by mutableStateOf(false)
    var status by mutableStateOf("")
    var error by mutableStateOf<String?>(null)
    val busy: Boolean get() = loading || waitingForPermission
    val isBusJourney: Boolean get() = selectedRoute?.kakao?.firstTransitType == "BUS" ||
        boarding?.let { it.isBusStop && !it.isSubwayStation } == true

    fun usableGyeonggiArrivals(nowMillis: Long): List<GyeonggiBusArrivalDisplay> {
        val match = selectedGyeonggiMatch ?: return emptyList()
        if (selectedBusRoute != null) return emptyList()
        return gyeonggiArrivals.filter { row ->
            row.matchesOfficialMatch(match) && isUsableGyeonggiArrival(row, nowMillis)
        }.sortedBy { it.arrivalBasisMillis(nowMillis) + requireNotNull(it.seconds) * 1_000L }
    }

    private val activeGyeonggiSnapshot: RealtimeArrivalSnapshot?
        get() = selectedGyeonggiMatch?.takeIf { selectedBusRoute == null }?.let {
            chooseMatchingGyeonggiArrival(it, gyeonggiArrivals, System.currentTimeMillis())?.snapshot
        }
    private val activeBusArrival: OfficialSeoulBusArrival?
        get() = selectedBusArrival?.takeIf {
            selectedBusStop?.stopId == it.identity.stopId && selectedBusRoute?.identity == it.identity
        }
    val activeArrivalIdentity: OfficialBoardingIdentity?
        get() = if (isBusJourney) activeBusArrival?.identity ?: activeGyeonggiSnapshot?.identity else selectedArrival?.identity
    val activeArrivalSnapshot: RealtimeArrivalSnapshot?
        get() = if (isBusJourney) activeBusArrival?.snapshot ?: activeGyeonggiSnapshot else selectedArrival?.snapshot
    val activeArrivalVehicleId: String?
        get() = if (isBusJourney) activeBusArrival?.vehicleId ?:
            (activeGyeonggiSnapshot?.let { snapshot ->
                gyeonggiArrivals.firstOrNull {
                    it.trust == ArrivalTrustState.VALID &&
                        it.stationId == snapshot.identity.stopId &&
                        it.routeId == snapshot.identity.routeId &&
                        it.staOrder == snapshot.identity.directionId &&
                        it.observedAtMillis?.plus((it.seconds ?: 0) * 1_000L) ==
                            snapshot.firstArrivalAtEpochMillis
                }?.vehicleId?.takeIf(String::isNotBlank)
            }) else selectedArrival?.trainId

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
        if (mode == FeatureMode.STOP && field == SearchField.DESTINATION) clearStopDestinationDirection()
        else clearRoutesAndBoarding()
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
        gyeonggiGeneration++
        gyeonggiLoading = false
        loading = false
        waitingForPermission = false
        liveLoading = false
        timetableAnalysis = null
        timetableError = null
    }

    /** A new destination keeps the selected physical station, but retires its old direction. */
    fun clearStopDestinationDirection() {
        invalidateRequests()
        stopDirectionRoute = null
        directionMessage = null
        confirmedServiceIdentity = null
        selectedArrival = null
        guidanceWalkingRoute = null
    }

    fun clearWalking() {
        invalidateRequests()
        discardWalkingResults()
        clearAccessTransit()
        location = null
    }

    fun clearAccessTransit() {
        accessTransitGeneration++
        accessTransitRoutes = emptyList()
        accessJourney = null
        accessTransitMessage = null
        accessTransitLoading = false
        accessTransitFetchedAtMillis = null
        accessTransitOrigin = null
        accessTransitAttemptAtMillis = null
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
        busRoutePathVerified = false
        busDirectionMessage = null
        lastGuidanceAssessment = null
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
        gyeonggiGeneration++
        selectedGyeonggiMatch = null
        gyeonggiStationId = ""
        gyeonggiArrivals = emptyList()
        gyeonggiMessage = null
        gyeonggiLoading = false
        arrivalRequestFailed = false
        liveError = null
        liveLoading = false
        guidanceWalkingRoute = null
    }

    /** A new bus route keeps the chosen physical stop but invalidates its old vehicle/identity. */
    fun clearSelectedBusRoute() {
        busRoutePathVerified = false
        busDirectionMessage = null
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
        stopDirectionRoute = null
        directionMessage = null
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

    /** GPS/walking updates may finish while this lookup runs; only a newer lookup/selection retires it. */
    fun applySeoulStopsResponse(generation: Long, expectedBoarding: JourneyPlace?, stops: List<OfficialSeoulBusStop>): Boolean {
        if (!acceptsLiveResponse(generation) || boarding != expectedBoarding) return false
        busStopOptions = stops
        busStopsSearched = true
        return true
    }

    fun centerOn(point: MapPoint) {
        mapCenter = point
        recenterRequest++
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
