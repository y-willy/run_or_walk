package com.example.run_or_walk

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.example.run_or_walk.ui.theme.RUN_or_WALKTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

// Preserved for the original manual calculator and its vibration patterns.
enum class Urgency { NONE, LOW, MEDIUM, HIGH, MISSED }

private enum class LocationAction { ROUTES, WALKING, RECENTER }
private data class LocationRequest(
    val screen: JourneyScreenState, val revision: Long, val action: LocationAction)
private data class LocationWorkResult(
    val location: GuidanceLocation,
    val walk: KakaoWalkingRoute? = null,
    val routes: List<KakaoTransitChoice>? = null)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_RUN_or_WALK)
        super.onCreate(savedInstanceState)
        val activityLifecycleOwner: LifecycleOwner = this
        val showLaunchArtwork = savedInstanceState == null
        setContent {
            RUN_or_WALKTheme {
                BrandLaunchGate(showOnLaunch = showLaunchArtwork) {
                    PaceNudgeScreen(lifecycleOwner = activityLifecycleOwner)
                }
            }
        }
    }
}

@Composable
fun PaceNudgeScreen(lifecycleOwner: LifecycleOwner) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val kakaoRepository = remember { KakaoApiRepository(BuildConfig.KAKAO_REST_KEY) }
    val subwayRepository = remember { SeoulSubwayRepository(context) }
    val busRepository = remember { SeoulBusRepository() }
    val screens = remember {
        val alerts = AutomaticAlertGate()
        FeatureMode.entries.associateWith { JourneyScreenState(it, alerts) }
    }
    var mode by rememberSaveable { mutableStateOf(FeatureMode.ROUTE) }
    val screen = screens.getValue(mode)
    val latestScreen by rememberUpdatedState(screen)
    var currentPoint by remember { mutableStateOf<MapPoint?>(null) }
    var pendingLocation by remember { mutableStateOf<LocationRequest?>(null) }
    var mapExpanded by rememberSaveable { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val vibrationSupported = remember(context) { supportsVibration(context) }

    fun <T> runRequest(target: JourneyScreenState, message: String, failureMessage: String,
        request: suspend () -> T, onResult: (T) -> Unit) {
        if (target.busy) return
        keyboard?.hide()
        val generation = target.beginRequest(message)
        scope.launch {
            try {
                val result = request()
                if (target.acceptsResponse(generation) && latestScreen === target) {
                    target.loading = false
                    onResult(result)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (target.acceptsResponse(generation) && latestScreen === target) {
                    target.status = ""
                    target.error = friendlyFailure(error, failureMessage)
                }
            } finally {
                if (target.acceptsResponse(generation)) target.loading = false
            }
        }
    }

    fun storeWalking(target: JourneyScreenState, location: GuidanceLocation,
        boarding: JourneyPlace, identity: OfficialBoardingIdentity?, walk: KakaoWalkingRoute) {
        target.walkRoute = WalkRoute(walk.distanceMeters, walk.durationSeconds)
        target.walkFetchedAtEpochMillis = System.currentTimeMillis()
        target.walkOrigin = location.point
        target.walkingPoints = walk.points
        target.guidanceWalkingRoute = identity?.let {
            GuidanceWalkingRoute(location.point, boarding.point, it, walk.durationSeconds,
                System.currentTimeMillis(), 120_000L)
        }
    }

    fun performLocationAction(target: JourneyScreenState, action: LocationAction) {
        val destination = target.destination.selected
        val boarding = target.boarding
        val identity = target.confirmedServiceIdentity
        val revision = target.revision
        if (action == LocationAction.ROUTES && destination == null) return
        if (action == LocationAction.WALKING && boarding == null) return
        runRequest(target,
            if (action == LocationAction.ROUTES) "현재 위치에서 경로를 찾고 있어요" else "현재 위치와 도보 경로를 확인하고 있어요",
            "위치·경로를 확인하지 못했어요",
            request = {
                val location = getCurrentLocationSample(context)
                if (target.revision != revision || latestScreen !== target) throw CancellationException()
                // A route provider failure must not discard a successful device fix.
                target.location = location
                currentPoint = location.point
                target.centerOn(location.point)
                target.status = when (action) {
                    LocationAction.ROUTES -> "현재 위치 확인 완료 · 대중교통 경로를 찾고 있어요"
                    LocationAction.WALKING -> "현재 위치 확인 완료 · 도보 경로를 찾고 있어요"
                    LocationAction.RECENTER -> "현재 위치를 갱신했어요."
                }
                when (action) {
                    LocationAction.ROUTES -> LocationWorkResult(location,
                        routes = kakaoRepository.searchPublicTransit(location.point, destination!!.point))
                    LocationAction.WALKING -> LocationWorkResult(location,
                        walk = kakaoRepository.searchWalk(location.point, boarding!!.point))
                    LocationAction.RECENTER -> LocationWorkResult(location)
                }
            },
            onResult = { result ->
                target.location = result.location
                currentPoint = result.location.point
                target.centerOn(result.location.point)
                result.routes?.let { routes ->
                    target.routes = routes.take(3).mapIndexed { index, route ->
                        JourneyTransitRoute("kakao-$index", route)
                    }
                    target.routeSearchCompleted = true
                    target.status = "추정 소요시간 순 · 실시간 도착정보 연결 전이에요."
                }
                result.walk?.let { walk ->
                    storeWalking(target, result.location, boarding!!, identity, walk)
                    target.status = "도보 경로를 확인했어요."
                }
                if (action == LocationAction.RECENTER) {
                    // Recentring changes the GPS sample. Never keep guidance tied to an old origin.
                    target.guidanceWalkingRoute = null
                    target.walkRoute = null
                    target.walkingPoints = emptyList()
                    target.status = "현재 위치를 갱신했어요."
                }
            })
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        val pending = pendingLocation
        pendingLocation = null
        pending?.let {
            val target = it.screen
            target.waitingForPermission = false
            val granted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
            if (target.revision == it.revision && latestScreen === target) {
                if (granted) performLocationAction(target, it.action)
                else {
                    target.location = null
                    target.error = "위치 권한이 필요해요. 앱 권한 설정에서 위치를 허용해 주세요."
                }
            }
        }
    }

    fun requestLocation(target: JourneyScreenState, action: LocationAction) {
        if (target.busy) return
        if (hasLocationPermission(context)) performLocationAction(target, action)
        else if (pendingLocation == null) {
            keyboard?.hide()
            target.waitingForPermission = true
            target.status = "현재 위치를 사용하려면 위치 권한을 허용해 주세요."
            pendingLocation = LocationRequest(target, target.revision, action)
            permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION))
        } else target.error = "진행 중인 위치 권한 요청을 완료해 주세요."
    }

    fun selectOfficialBusRoute(target: JourneyScreenState, route: OfficialSeoulBusRoute) {
        val stop = target.selectedBusStop ?: return
        if (!route.supported || route.stopId != stop.stopId || route !in target.busRouteOptions) return
        target.invalidateRequests()
        target.clearSelectedBusRoute()
        target.selectedBusRoute = route
        target.confirmedServiceIdentity = route.identity
        target.liveError = null
    }

    fun selectOfficialBusStop(target: JourneyScreenState, stop: OfficialSeoulBusStop,
        automatic: Boolean = false) {
        val point = stop.point ?: return
        target.clearWalking()
        target.clearOfficialSelection()
        target.boarding = JourneyPlace("seoul-bus-${stop.stopId}", stop.name,
            "정류장 번호 ${stop.arsId}", point, categoryName = "버스정류장")
        target.boardingSource = BoardingPointSource.VERIFIED
        target.boardingChosenByUser = !automatic
        target.boardingMatchedAutomatically = automatic
        target.selectedBusStop = stop
        target.busStopOptions = listOf(stop)
        target.busStopsSearched = true
        target.busStopQuery = stop.name
        target.changingBoarding = false
        target.centerOn(point)
        val generation = target.beginLiveRequest()
        scope.launch {
            try {
                val routes = busRepository.routes(stop)
                if (target.acceptsLiveResponse(generation) && latestScreen === target && target.selectedBusStop == stop) {
                    target.busRouteOptions = routes
                    val matched = resolveOfficialSeoulBusRoute(routes, stop.stopId,
                        target.selectedRoute?.serviceName.orEmpty())
                    if (matched != null) {
                        target.selectedBusRoute = matched
                        target.confirmedServiceIdentity = matched.identity
                    }
                    target.liveError = when {
                        routes.isEmpty() -> "이 정류장에 조회 가능한 노선이 없어요."
                        routes.none { it.supported } -> "이 정류장에는 서울 소속 지원 노선이 없어요. 경기·인천 노선은 별도 연동이 필요해요."
                        else -> null
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (target.acceptsLiveResponse(generation) && latestScreen === target) {
                    target.liveRetryAtEpochMillis = (error as? SeoulBusApiException)?.retryAfterEpochMillis
                    target.liveError = (error as? SeoulBusApiException)?.message ?: "정류장의 버스 노선을 확인하지 못했어요."
                }
            } finally {
                if (target.acceptsLiveResponse(generation)) target.liveLoading = false
            }
        }
    }

    fun findOfficialBusStops(target: JourneyScreenState, nearby: Boolean = false,
        automatic: Boolean = false) {
        val boarding = target.boarding ?: return
        val query = target.busStopQuery.trim().ifBlank { boarding.name }
        if (target.liveLoading) return
        target.clearSelectedBusRoute()
        target.selectedBusStop = null
        target.busRouteOptions = emptyList()
        target.busStopOptions = emptyList()
        target.busStopsSearched = false
        val generation = target.beginLiveRequest()
        scope.launch {
            try {
                val stops = if (nearby) busRepository.nearbyStops(boarding.point)
                    else busRepository.searchStops(query)
                if (target.acceptsLiveResponse(generation) && latestScreen === target && target.boarding == boarding) {
                    target.busStopOptions = stops
                    target.busStopsSearched = true
                    val matched = if (automatic) resolveOfficialSeoulBusStop(stops, boarding) else null
                    if (matched != null) selectOfficialBusStop(target, matched, automatic = true)
                    else target.liveError = if (stops.isEmpty())
                        "서울 공식 정류장이 없어요. 정류장 이름이나 표지의 5자리 번호로 검색해 주세요." else null
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (target.acceptsLiveResponse(generation) && latestScreen === target) {
                    target.liveRetryAtEpochMillis = (error as? SeoulBusApiException)?.retryAfterEpochMillis
                    target.liveError = (error as? SeoulBusApiException)?.message ?: "서울 공식 정류장을 확인하지 못했어요."
                }
            } finally {
                if (target.acceptsLiveResponse(generation)) target.liveLoading = false
            }
        }
    }

    fun findOfficialStations(target: JourneyScreenState) {
        if (target.boarding?.isBusStop == true && target.boarding?.isSubwayStation != true ||
            target.selectedRoute?.kakao?.firstTransitType == "BUS") {
            target.busStopQuery = target.boarding?.name.orEmpty()
            findOfficialBusStops(target, nearby = true, automatic = true)
            return
        }
        val query = target.stationQuery.trim()
        if (query.isBlank()) return
        val generation = target.beginLiveRequest()
        scope.launch {
            try {
                val stations = subwayRepository.searchStations(query)
                if (target.acceptsLiveResponse(generation) && latestScreen === target) {
                    target.stationOptions = stations
                    target.stationsSearched = true
                    if (target.boardingConfirmed) {
                        val matched = resolveOfficialBoardingStation(stations,
                            target.boarding?.name.orEmpty(), target.selectedRoute?.serviceName)
                        if (matched != null) {
                            target.selectedStation = matched
                            target.lastArrivalPollElapsedMillis = null
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (target.acceptsLiveResponse(generation)) target.liveError = "공식 역 목록을 확인하지 못했어요."
            } finally {
                if (target.acceptsLiveResponse(generation)) target.liveLoading = false
            }
        }
    }

    val walkingRefreshes = remember { mutableSetOf<JourneyScreenState>() }
    suspend fun refreshWalkingSnapshot(target: JourneyScreenState) {
        val boarding = target.boarding ?: return
        if (target.busy || latestScreen !== target || !walkingRefreshes.add(target)) return
        val revision = target.revision
        val requestId = target.requestId
        val identity = target.confirmedServiceIdentity
        fun active() = target.revision == revision && latestScreen === target &&
            target.requestId == requestId && !target.busy &&
            target.boarding == boarding && target.confirmedServiceIdentity == identity
        try {
            val location = getCurrentLocationSample(context)
            if (!active()) return
            target.location = location
            currentPoint = location.point
            val fetched = target.walkFetchedAtEpochMillis
            val origin = target.walkOrigin
            val walk = target.walkRoute
            val walkFresh = fetched != null && origin != null && walk != null &&
                System.currentTimeMillis() - fetched in 0L..90_000L &&
                distanceBetweenMeters(origin, location.point) <= 25.0
            if (walkFresh) {
                // Rebind the same actual, fresh walking response after a direction choice.
                target.guidanceWalkingRoute = identity?.let {
                    GuidanceWalkingRoute(requireNotNull(origin), boarding.point, it,
                        requireNotNull(walk).durationSeconds, requireNotNull(fetched), 120_000L)
                }
            } else {
                target.guidanceWalkingRoute = null
                target.walkRoute = null
                target.walkingPoints = emptyList()
                val refreshedWalk = kakaoRepository.searchWalk(location.point, boarding.point)
                if (active()) storeWalking(target, location, boarding, identity, refreshedWalk)
            }
            if (active()) target.error = null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (active()) {
                // Preserve an acquired GPS fix even when only the route request failed.
                target.guidanceWalkingRoute = null
                target.error = friendlyFailure(error, "도보 경로를 다시 확인해 주세요")
            }
        } finally {
            walkingRefreshes.remove(target)
        }
    }

    suspend fun refreshBusLive(target: JourneyScreenState, force: Boolean = false) {
        val stop = target.selectedBusStop ?: return
        val route = target.selectedBusRoute ?: return
        val boarding = target.boarding ?: return
        if (target.liveLoading || latestScreen !== target || !busRepository.isConfigured) return
        if (target.liveRetryAtEpochMillis?.let { it > System.currentTimeMillis() } == true) return
        val binding = target.confirmedServiceIdentity
        if (binding != route.identity || route.stopId != stop.stopId || !route.supported) return
        val generation = target.beginLiveRequest()
        fun active() = target.acceptsLiveResponse(generation) && latestScreen === target &&
            target.selectedBusStop == stop && target.selectedBusRoute == route &&
            target.boarding == boarding && target.confirmedServiceIdentity == binding
        try {
            val elapsed = SystemClock.elapsedRealtime()
            val previous = target.lastArrivalPollElapsedMillis
            val arrivals = if (force || previous == null || elapsed - previous >= 30_000L) {
                target.lastArrivalPollElapsedMillis = elapsed
                busRepository.arrivals(stop, route)
            } else target.busArrivalOptions
            if (!active()) return
            target.busArrivalOptions = arrivals
            target.selectedBusArrival = chooseMatchingBusArrival(binding, arrivals, System.currentTimeMillis())
            target.arrivalRequestFailed = false
            target.liveRetryAtEpochMillis = null
            target.liveError = when {
                arrivals.isEmpty() -> "선택한 노선에 도착 예정 버스가 없어요. 운행 상태를 확인해 주세요."
                target.selectedBusArrival == null -> arrivals.map {
                    seoulBusArrivalDisplayStatus(it, System.currentTimeMillis())
                }.distinct().joinToString("\n")
                else -> null
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            if (active()) {
                target.arrivalRequestFailed = true
                target.liveRetryAtEpochMillis = (error as? SeoulBusApiException)?.retryAfterEpochMillis
                target.liveError = (error as? SeoulBusApiException)?.message ?: "버스 도착정보를 가져오지 못했어요."
            }
        } finally {
            if (target.acceptsLiveResponse(generation)) target.liveLoading = false
        }
    }

    suspend fun refreshLive(target: JourneyScreenState, force: Boolean = false) {
        if (target.isBusJourney) { refreshBusLive(target, force); return }
        val station = target.selectedStation ?: return
        val boarding = target.boarding ?: return
        if (target.liveLoading || latestScreen !== target) return
        if (!subwayRepository.isConfigured) {
            target.liveError = "실시간 정보 연결 필요"
            return
        }
        target.liveRetryAtEpochMillis?.takeIf { it > System.currentTimeMillis() }?.let {
            // Keep the actual reason (warmup, IP limit or daily budget) during backoff.
            if (target.liveError == null) target.liveError = "서버의 재조회 대기 시간 이후 자동으로 다시 확인해요."
            return
        }
        val selected = target.selectedArrival
        val binding = target.confirmedServiceIdentity
        var expectedSelection = selected?.let { it.identity to it.trainId }
        val generation = target.beginLiveRequest()
        fun active() = target.acceptsLiveResponse(generation) && latestScreen === target &&
            target.selectedStation == station && target.boarding == boarding &&
            target.confirmedServiceIdentity == binding &&
            target.selectedArrival?.let { it.identity to it.trainId } == expectedSelection
        try {
            val elapsed = SystemClock.elapsedRealtime()
            val lastPoll = target.lastArrivalPollElapsedMillis
            val shouldFetch = force || lastPoll == null || elapsed - lastPoll >= 30_000L
            val arrivals = if (shouldFetch) {
                target.lastArrivalPollElapsedMillis = elapsed
                subwayRepository.arrivals(station)
            } else target.arrivalOptions
            if (!active()) return
            target.arrivalOptions = arrivals
            target.liveRetryAtEpochMillis = null
            val refreshed = chooseMatchingSubwayArrival(binding, arrivals, System.currentTimeMillis())
            if (binding == null) {
                target.arrivalRequestFailed = false
                target.liveError = if (arrivals.isEmpty()) "도착 예정 차량이 없어요."
                    else subwayArrivalDiagnosticSummary(arrivals, System.currentTimeMillis())
                return
            }
            // A confirmed full service identity is required before choosing an actual vehicle.
            if (refreshed == null) {
                target.arrivalRequestFailed = false
                target.selectedArrival = null
                expectedSelection = null
                target.liveError = subwayArrivalDiagnosticSummary(arrivals, System.currentTimeMillis())
                    ?: "선택한 방향의 열차 정보가 없어요. 다른 방향을 확인하거나 다음 갱신을 기다려 주세요."
                return
            }
            target.selectedArrival = refreshed
            expectedSelection = refreshed.identity to refreshed.trainId
            target.arrivalRequestFailed = false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (active()) {
                target.arrivalRequestFailed = true
                target.liveRetryAtEpochMillis = (error as? SeoulSubwayApiException)?.retryAfterEpochMillis
                target.liveError = (error as? SeoulSubwayApiException)?.message
                    ?: "실시간 도착정보를 가져오지 못했어요. 네트워크 연결을 확인해 주세요."
            }
        } finally {
            if (target.acceptsLiveResponse(generation)) target.liveLoading = false
        }
    }

    fun selectOfficialStation(target: JourneyScreenState, station: OfficialSubwayStation) {
        target.invalidateRequests()
        target.selectedStation = station
        target.confirmedServiceIdentity = null
        target.selectedArrival = null
        target.arrivalOptions = emptyList()
        target.arrivalRequestFailed = false
        target.guidanceWalkingRoute = null
        target.lastArrivalPollElapsedMillis = null
        scope.launch { refreshLive(target, force = true) }
    }

    fun searchPlaces(target: JourneyScreenState, field: SearchField) {
        val input = target.search(field)
        val query = input.query.trim()
        if (target.busy || query.isBlank()) return
        target.clearRoutesAndBoarding()
        input.selected = null
        input.options = emptyList()
        input.searched = false
        runRequest(target, "장소를 찾고 있어요", "장소를 검색하지 못했어요",
            request = {
                (if (field == SearchField.STOP) kakaoRepository.searchStops(query)
                    else kakaoRepository.searchPlaces(query)).map(JourneyPlace::from)
            },
            onResult = { places -> input.options = places; input.searched = true; target.status = "" })
    }

    fun selectPlace(target: JourneyScreenState, field: SearchField, place: JourneyPlace) {
        target.clearRoutesAndBoarding()
        target.search(field).apply { query = place.name; selected = place; options = emptyList() }
        target.centerOn(place.point)
        if (field == SearchField.DESTINATION) requestLocation(target, LocationAction.ROUTES)
        else {
            target.boarding = place
            target.boardingChosenByUser = true
            target.boardingSource = BoardingPointSource.VERIFIED
            target.stationQuery = officialStationSearchQuery(place.name)
            target.detailsExpanded = true
            findOfficialStations(target)
            requestLocation(target, LocationAction.WALKING)
        }
    }

    fun confirmBoarding(target: JourneyScreenState, place: JourneyPlace, automatic: Boolean = false) {
        target.clearWalking()
        target.clearOfficialSelection()
        target.boarding = place
        target.boardingChosenByUser = !automatic
        target.boardingMatchedAutomatically = automatic
        target.boardingSource = BoardingPointSource.VERIFIED
        target.boardingCandidates = emptyList()
        target.candidatesSearched = false
        target.changingBoarding = false
        target.stationQuery = officialStationSearchQuery(place.name)
        target.centerOn(place.point)
        findOfficialStations(target)
        requestLocation(target, LocationAction.WALKING)
    }

    fun findBoardingPlaces(target: JourneyScreenState, automatic: Boolean = false) {
        val route = target.selectedRoute?.kakao
        val name = target.selectedRoute?.boardingName ?: target.boarding?.name ?: return
        if (name.isBlank()) return
        target.boardingCandidates = emptyList()
        target.candidatesSearched = false
        runRequest(target, "경로의 승차역을 확인하고 있어요", "승차 장소를 검색하지 못했어요",
            request = { kakaoRepository.searchStops(name).map(JourneyPlace::from) },
            onResult = { places ->
                target.boardingCandidates = places
                target.candidatesSearched = true
                val matched = if (automatic && route != null) resolveBoardingPlace(route, places) else null
                if (matched != null) confirmBoarding(target, matched, automatic = true)
                else {
                    target.detailsExpanded = true
                    target.changingBoarding = true
                    target.status = "승차 장소 후보에서 이용할 역을 선택해 주세요."
                }
            })
    }

    fun selectRoute(target: JourneyScreenState, route: JourneyTransitRoute) {
        if (target.busy) return
        target.clearWalking()
        target.clearOfficialSelection()
        target.selectedRoute = route
        target.boardingSource = route.kakao.boardingSource
        target.boardingChosenByUser = false
        target.boardingMatchedAutomatically = false
        target.boardingCandidates = emptyList()
        target.candidatesSearched = false
        target.boarding = route.kakao.boardingPoint?.let {
            JourneyPlace("boarding-${route.id}", route.boardingName, route.serviceName, it)
        }
        target.stationQuery = officialStationSearchQuery(route.boardingName)
        target.detailsExpanded = true
        target.changingBoarding = true
        target.error = null
        target.boarding?.let { target.centerOn(it.point) }
        // A unique, actual station search result can confirm the place without a second tap.
        findBoardingPlaces(target, automatic = true)
    }

    LaunchedEffect(screen) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (isActive) { now = System.currentTimeMillis(); delay(1_000L) }
        }
    }
    LaunchedEffect(screen, screen.boarding, screen.confirmedServiceIdentity) {
        if (screen.boarding != null) {
            lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (isActive) { refreshWalkingSnapshot(screen); delay(20_000L) }
            }
        }
    }
    val selectedTrainKey = screen.activeArrivalIdentity to screen.activeArrivalVehicleId
    LaunchedEffect(screen, screen.selectedStation, screen.selectedBusStop, screen.selectedBusRoute, screen.confirmedServiceIdentity) {
        if (screen.selectedStation != null && subwayRepository.isConfigured ||
            screen.selectedBusRoute != null && busRepository.isConfigured) {
            lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (isActive) { refreshLive(screen); delay(30_000L) }
            }
        }
    }

    val selectedArrival = screen.selectedArrival
    val selectedBusArrival = screen.selectedBusArrival
    val isBus = screen.isBusJourney
    val configured = if (isBus) busRepository.isConfigured else subwayRepository.isConfigured
    val activeSnapshot = screen.activeArrivalSnapshot
    val boarding = screen.boarding
    val identity = screen.activeArrivalIdentity
    val officialConnectionConfirmed = if (isBus) {
        val route = screen.selectedBusRoute
        route != null && screen.selectedBusStop?.stopId == route.stopId && route.identity == identity
    } else screen.selectedStation?.let {
        it.stopId == identity?.stopId && it.routeId == identity.routeId
    } == true
    val guidanceBoarding = if (identity != null && boarding != null) GuidanceBoarding(
        identity, boarding.point, screen.boardingConfirmed && officialConnectionConfirmed) else null
    val assessment = calculateAutoGuidance(AutoGuidanceInput(
        location = screen.location, boarding = guidanceBoarding,
        walkingRoute = screen.guidanceWalkingRoute,
        arrivals = activeSnapshot.takeIf { configured },
        locationPermissionGranted = hasLocationPermission(context),
        arrivalRequestFailed = screen.arrivalRequestFailed), now)
    val validArrival = activeSnapshot?.takeIf {
        configured && !screen.arrivalRequestFailed && it.reliable &&
            it.trustState == ArrivalTrustState.VALID &&
            now - it.fetchedAtEpochMillis in 0L..minOf(it.validForMillis, 60_000L) &&
            it.firstArrivalAtEpochMillis > now
    }
    val arrivalDiagnostic = if (isBus) {
        if (screen.busArrivalOptions.isNotEmpty() && validArrival == null) screen.liveError
            ?: "확인된 버스의 도착 상태를 갱신하고 있어요." else null
    } else screen.arrivalOptions.takeIf { it.isNotEmpty() }
        ?.let { subwayArrivalDiagnosticSummary(it, now) }
    val officialStopSelected = if (isBus) screen.selectedBusStop != null else screen.selectedStation != null
    val hasArrivalOptions = if (isBus) screen.busArrivalOptions.isNotEmpty() else screen.arrivalOptions.isNotEmpty()
    val unknownMessage = when {
        screen.boarding == null && screen.selectedRoute == null ->
            "목적지나 이용할 정류장·역을 검색하면 현재 위치와 도착정보를 연결해요."
        !configured -> "실시간 정보 연결 필요"
        screen.busy -> screen.status
        !screen.boardingConfirmed -> "승차 장소 후보가 여러 개이거나 정확히 일치하지 않아요. 아래 후보에서 이용할 장소를 선택해 주세요."
        !officialStopSelected && screen.liveError != null -> screen.liveError.orEmpty()
        !officialStopSelected -> if (isBus) "서울 공식 정류장을 선택해 주세요." else "공식 역·호선을 선택해 주세요."
        isBus && screen.selectedBusRoute == null -> screen.liveError ?: "탑승할 버스 노선·방향을 선택해 주세요."
        screen.liveRetryAtEpochMillis?.let { it > now } == true ->
            (screen.liveError ?: "서버 안내 시각까지 기다려 주세요.") +
                " · 재조회까지 ${((requireNotNull(screen.liveRetryAtEpochMillis) - now) / 1_000L).coerceAtLeast(1)}초"
        screen.arrivalRequestFailed -> screen.liveError ?: "실시간 도착정보를 확인하지 못했어요. 자동으로 다시 조회해요."
        arrivalDiagnostic != null -> arrivalDiagnostic
        screen.liveLoading && !hasArrivalOptions -> "선택한 승차 장소의 도착정보를 받고 있어요."
        !hasArrivalOptions && screen.liveError != null -> screen.liveError.orEmpty()
        activeSnapshot == null -> if (isBus) "버스 도착정보를 확인하고 있어요." else "탑승할 노선·방향을 확인해 주세요."
        else -> unknownReasonText(assessment.unknownReason)
    }
    val unknownTitle = when {
        screen.boarding == null && screen.selectedRoute == null -> "이동 안내를 시작해 보세요"
        screen.busy || screen.liveLoading && !hasArrivalOptions -> "이동 정보를 확인하고 있어요"
        !screen.boardingConfirmed -> "승차 장소를 확인해 주세요"
        !officialStopSelected -> if (isBus) "이용할 정류장을 선택해 주세요" else "이용할 호선을 선택해 주세요"
        isBus && screen.selectedBusRoute == null -> "탑승할 버스·방향을 선택해 주세요"
        screen.arrivalRequestFailed || arrivalDiagnostic != null -> "도착정보를 확인하고 있어요"
        activeSnapshot == null -> if (isBus) "버스 도착정보를 확인하고 있어요" else "탑승할 방향을 선택해 주세요"
        else -> "자동 판단을 준비하고 있어요"
    }
    LaunchedEffect(screen, assessment.status, selectedTrainKey, screen.alertsEnabled) {
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
            screen.claimAutomaticAlert(assessment.status, now) && vibrationSupported) {
            playVibration(context, when (assessment.status) {
                AutoGuidanceStatus.WALK -> Urgency.LOW
                AutoGuidanceStatus.FAST_WALK -> Urgency.HIGH
                AutoGuidanceStatus.NEXT_TRAIN -> Urgency.MEDIUM
                AutoGuidanceStatus.UNKNOWN -> Urgency.NONE
            })
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()
            .verticalScroll(screen.scrollState).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // 1. Tabs. Screen states survive switching; pending responses do not.
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                PaceNudgeHeader()
                FeatureModeSelector(mode.ordinal, onSelect = { index ->
                    keyboard?.hide()
                    if (mode != FeatureMode.entries[index]) {
                        screen.invalidateRequests()
                        mode = FeatureMode.entries[index]
                    }
                })
            }
            // 2. One search input. A destination never requires a manually entered origin.
            SearchContent(screen, onSearch = { searchPlaces(screen, it) },
                onEdit = { field, value -> screen.editQuery(field, value) },
                onChoose = { field, place -> selectPlace(screen, field, place) },
                onRetryRoutes = {
                    screen.clearRoutesAndBoarding()
                    requestLocation(screen, LocationAction.ROUTES)
                })
            // 3. Core results and optional selection details.
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (screen.busy) LoadingStateCard(screen.status)
                screen.error?.let {
                    NoticeCard(it, isError = true)
                    if (it.contains("위치 권한")) TextButton(onClick = {
                        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:${context.packageName}")))
                    }) { Text("위치 권한 설정") }
                    TextButton(onClick = { requestLocation(screen,
                        if (screen.boarding != null) LocationAction.WALKING
                        else if (screen.destination.selected != null) LocationAction.ROUTES
                        else LocationAction.RECENTER) }, enabled = !screen.busy) {
                        Text("현재 위치·경로 다시 확인")
                    }
                    if (it.contains("위치 서비스") || it.contains("위치 설정")) TextButton(onClick = {
                        context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                    }) { Text("기기 위치 설정") }
                }
                AutomaticGuidanceCard(assessment,
                    serviceLabel = if (isBus) screen.selectedBusRoute?.let { "${it.name} · ${it.direction}" }
                        ?: screen.selectedRoute?.serviceName.orEmpty()
                        else selectedArrival?.let { "${screen.selectedStation?.lineName.orEmpty()} · ${it.trainLabel} · ${it.directionLabel}" }
                        ?: screen.selectedRoute?.serviceName.orEmpty(),
                    boardingLabel = boarding?.name, unknownMessage = unknownMessage,
                    unknownTitle = unknownTitle,
                    vehicleLabel = if (isBus) "버스" else "열차",
                    movementHint = if (isBus) "정류장 접근과 횡단보도 신호를 확인해 주세요." else "역 내부 이동과 신호 대기는 확인해 주세요.",
                    arrivalInformation = if (hasArrivalOptions && validArrival == null)
                        "서버 응답 수신 · 자동 판단 조건 확인 중" else null,
                    nowEpochMillis = now,
                    firstArrivalAtEpochMillis = validArrival?.firstArrivalAtEpochMillis,
                    nextArrivalAtEpochMillis = validArrival?.nextArrivalAtEpochMillis,
                    arrivalFetchedAtEpochMillis = validArrival?.fetchedAtEpochMillis,
                    walkingSeconds = screen.walkRoute?.durationSeconds?.takeIf {
                        screen.location?.let { fix ->
                            now - fix.observedAtEpochMillis in 0L..30_000L && fix.accuracyMeters <= 50.0
                        } == true && (screen.walkFetchedAtEpochMillis?.let { fetched ->
                            now - fetched in 0L..120_000L && screen.walkOrigin?.let { origin ->
                                screen.location?.let { distanceBetweenMeters(it.point, origin) <= 35.0 } == true
                            } == true
                        } ?: false)
                    })
                if (screen.routes.isNotEmpty()) {
                    Text("경로 · 추정 소요시간 순", style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.semantics { heading() })
                    screen.routes.take(3).forEach { route ->
                        key(route.id) {
                            TransitRouteCard(route.durationMinutes, route.serviceName, route.boardingName,
                                route.detail, screen.selectedRoute?.id == route.id, enabled = !screen.busy,
                                guidanceLabel = if (screen.selectedRoute?.id == route.id &&
                                    assessment.status != AutoGuidanceStatus.UNKNOWN) "실시간 도착정보 연결됨"
                                    else "추정 · 실시간 미연결",
                                onSelect = { selectRoute(screen, route) })
                        }
                    }
                } else if (screen.routeSearchCompleted && !screen.busy) {
                    EmptyStateCard("검색된 경로가 없어요", "목적지를 바꿔 검색해 주세요.")
                }

                if (boarding != null || screen.selectedRoute != null) {
                    TextButton(onClick = { screen.detailsExpanded = !screen.detailsExpanded },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text(if (screen.detailsExpanded) "승차·실시간 연결 접기" else "승차 변경 · 실시간 연결")
                    }
                    if (screen.detailsExpanded) {
                        SelectionDetails(screen, configured, now,
                            onBusQueryChange = { screen.editBusStopQuery(it) },
                            onSearchBusStops = { findOfficialBusStops(screen) },
                            onNearbyBusStops = { findOfficialBusStops(screen, nearby = true) },
                            onChooseBusStop = { selectOfficialBusStop(screen, it) },
                            onChooseBusRoute = { selectOfficialBusRoute(screen, it) },
                            onChangeBoarding = { screen.changingBoarding = true; findBoardingPlaces(screen) },
                            onFindBoarding = { findBoardingPlaces(screen) },
                            onChooseBoarding = { confirmBoarding(screen, it) },
                            onStationQuery = {
                                screen.editStationQuery(it)
                            },
                            onFindStations = { findOfficialStations(screen) },
                            onChooseStation = { selectOfficialStation(screen, it) },
                            onChooseArrival = {
                                screen.invalidateRequests()
                                screen.guidanceWalkingRoute = null
                                screen.confirmedServiceIdentity = it.identity
                                screen.selectedArrival = chooseMatchingSubwayArrival(it.identity,
                                    screen.arrivalOptions, System.currentTimeMillis())
                                screen.arrivalRequestFailed = false
                                screen.liveError = null
                                scope.launch { refreshWalkingSnapshot(screen) }
                            },
                            onRefresh = { scope.launch { refreshLive(screen, force = true) } })
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("자동 진동 알림", style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f))
                        Switch(checked = screen.alertsEnabled,
                            onCheckedChange = { screen.alertsEnabled = it }, enabled = vibrationSupported,
                            modifier = Modifier.semantics { contentDescription = "자동 진동 알림" })
                    }
                }
            }
            // 4. One MapView at a stable call site. Collapsing only changes its height.
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("현재 위치와 도보 경로", style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f))
                    TextButton(onClick = { mapExpanded = !mapExpanded }) {
                        Text(if (mapExpanded) "지도 줄이기" else "지도 펼치기")
                    }
                }
                KakaoMapPanel(lifecycleOwner = lifecycleOwner, center = screen.mapCenter,
                    routePoints = screen.walkingPoints, currentPoint = currentPoint,
                    boardingPoint = boarding?.point, recenterRequest = screen.recenterRequest,
                    onCurrentLocation = { requestLocation(screen,
                        if (boarding != null) LocationAction.WALKING else LocationAction.RECENTER) },
                    modifier = Modifier.fillMaxWidth().height(if (mapExpanded) 360.dp else 180.dp))
                if (screen.walkRoute != null && screen.walkingPoints.size < 2) NoticeCard(
                    "도보 요약만 제공돼 지도 경로를 표시할 수 없어요.")
                Text("서울 지하철·서울 소속 버스와 광역버스 지원 · 경기·인천 소속 버스는 별도 연동이 필요해요.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://data.seoul.go.kr/dataList/OA-12764/A/1/datasetView.do")))
                }) { Text("역·도착정보: 서울특별시 TOPIS · 공공누리 1유형",
                    style = MaterialTheme.typography.labelSmall) }
                if (isBus) TextButton(onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://www.data.go.kr/data/15000314/openapi.do")))
                }) { Text("버스 도착정보: 서울특별시 · 공공누리 1유형", style = MaterialTheme.typography.labelSmall) }
            }
        }
    }
}

@Composable
private fun SearchContent(screen: JourneyScreenState,
    onSearch: (SearchField) -> Unit, onEdit: (SearchField, String) -> Unit,
    onChoose: (SearchField, JourneyPlace) -> Unit, onRetryRoutes: () -> Unit) {
    val field = if (screen.mode == FeatureMode.ROUTE) SearchField.DESTINATION else SearchField.STOP
    val input = screen.search(field)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        LocationSearchInput(
            label = if (screen.mode == FeatureMode.ROUTE) "목적지" else "정류장·역 이름",
            input = input, enabled = !screen.busy,
            onChange = { onEdit(field, it) }, onSearch = { onSearch(field) },
            onChoose = { onChoose(field, it) })
        if (screen.mode == FeatureMode.ROUTE) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("현재 GPS 위치에서 출발", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                if (input.selected != null) TextButton(onClick = onRetryRoutes,
                    enabled = !screen.busy) { Text("경로 다시 찾기") }
            }
        }
    }
}

@Composable
private fun LocationSearchInput(label: String, input: JourneySearchState, enabled: Boolean,
    onChange: (String) -> Unit, onSearch: () -> Unit, onChoose: (JourneyPlace) -> Unit) {
    val canSearch = enabled && input.query.isNotBlank()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(value = input.query, onValueChange = onChange, label = { Text(label) },
            enabled = enabled, singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { if (canSearch) onSearch() }),
            trailingIcon = { TextButton(onClick = onSearch, enabled = canSearch,
                modifier = Modifier.heightIn(min = 48.dp)) { Text("검색") } },
            modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium)
        PlacePages(input.options, enabled, onChoose)
        if (input.searched && input.options.isEmpty() && input.selected == null && enabled) {
            EmptyStateCard("검색 결과가 없어요", "정확한 장소명으로 다시 검색해 주세요.")
        }
    }
}

@Composable
private fun PlacePages(places: List<JourneyPlace>, enabled: Boolean,
    onChoose: (JourneyPlace) -> Unit) {
    var page by remember(places) { mutableIntStateOf(0) }
    places.drop(page * 3).take(3).forEach { place ->
        key(place.id) { PlaceOptionCard(place.name, place.detail, enabled = enabled) { onChoose(place) } }
    }
    PageControls(page, places.size, { page = it })
}

@Composable
private fun PageControls(page: Int, count: Int, onPage: (Int) -> Unit) {
    if (count > 3) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween) {
        TextButton(onClick = { onPage(page - 1) }, enabled = page > 0) { Text("이전") }
        Text("${page + 1} / ${(count + 2) / 3}", style = MaterialTheme.typography.labelSmall)
        TextButton(onClick = { onPage(page + 1) }, enabled = (page + 1) * 3 < count) { Text("다음 결과") }
    }
}

@Composable
private fun SelectionDetails(screen: JourneyScreenState, configured: Boolean, nowEpochMillis: Long,
    onBusQueryChange: (String) -> Unit, onSearchBusStops: () -> Unit, onNearbyBusStops: () -> Unit,
    onChooseBusStop: (OfficialSeoulBusStop) -> Unit, onChooseBusRoute: (OfficialSeoulBusRoute) -> Unit,
    onChangeBoarding: () -> Unit, onFindBoarding: () -> Unit,
    onChooseBoarding: (JourneyPlace) -> Unit, onStationQuery: (String) -> Unit,
    onFindStations: () -> Unit, onChooseStation: (OfficialSubwayStation) -> Unit,
    onChooseArrival: (OfficialSubwayArrival) -> Unit, onRefresh: () -> Unit) {
    SectionCard(title = "승차 장소·차량 선택") {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(screen.boarding?.name ?: "승차 장소 확인 필요",
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            if (screen.boardingConfirmed) TextButton(onClick = onChangeBoarding,
                enabled = !screen.busy) { Text("변경") }
        }
        screen.boardingNotice?.let { NoticeCard(it) }
        if (!screen.boardingConfirmed || screen.changingBoarding) {
            OutlinedButton(onClick = onFindBoarding, enabled = !screen.busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("지도 승차 장소 선택") }
            PlacePages(screen.boardingCandidates, !screen.busy, onChooseBoarding)
            if (screen.candidatesSearched && screen.boardingCandidates.isEmpty()) {
                NoticeCard("승차 후보가 없어요. 정류장 찾기에서 정확한 장소를 검색해 주세요.")
            }
        }
        if (screen.boardingConfirmed) {
            if (screen.isBusJourney) {
                SeoulBusSelectionPanel(screen, nowEpochMillis, onBusQueryChange, onSearchBusStops,
                    onNearbyBusStops, onChooseBusStop, onChooseBusRoute, onRefresh)
            } else {
                NoticeCard(if (screen.selectedStation != null)
                    "승차 장소와 공식 역·호선을 연결했어요. 탑승할 방향을 확인해 주세요."
                    else "환승역은 이용할 호선을 선택해 주세요. 정확히 일치하는 역·호선은 자동 연결해요.")
                OutlinedTextField(value = screen.stationQuery, onValueChange = onStationQuery,
                    label = { Text("공식 역 이름") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onFindStations() }),
                    trailingIcon = { TextButton(onClick = onFindStations,
                        enabled = screen.stationQuery.isNotBlank()) { Text("찾기") } },
                    modifier = Modifier.fillMaxWidth())
                var stationPage by remember(screen.stationOptions) { mutableIntStateOf(0) }
                screen.stationOptions.drop(stationPage * 3).take(3).forEach { station ->
                    key(station.stopId to station.routeId) {
                        PlaceOptionCard("${station.name} · ${station.lineName}",
                            "지도에서 선택한 승차역과 연결",
                            selected = screen.selectedStation == station,
                            enabled = !screen.liveLoading) { onChooseStation(station) }
                    }
                }
                PageControls(stationPage, screen.stationOptions.size, { stationPage = it })
                if (screen.stationsSearched && screen.stationOptions.isEmpty()) NoticeCard(
                    "서울 API 공식 역 목록에 없어요. 다른 역 이름을 확인해 주세요.")
                if (!configured) NoticeCard("실시간 정보 연결 필요 · 공식 역 선택은 저장돼요.")
                screen.selectedStation?.let { station ->
                    if (screen.stationOptions.isEmpty()) Text("${station.name} · ${station.lineName}",
                        style = MaterialTheme.typography.bodyMedium)
                    if (configured) {
                        Text("탑승할 방향·행선지를 확인해 주세요", style = MaterialTheme.typography.labelMedium)
                        var arrivalPage by remember(screen.arrivalOptions.map { it.identity to it.trainId }) {
                            mutableIntStateOf(0)
                        }
                        val displayNow = nowEpochMillis
                        screen.arrivalOptions.drop(arrivalPage * 3).take(3).forEachIndexed { rowIndex, arrival ->
                            key(arrival.identity, arrival.trainId, rowIndex) {
                                PlaceOptionCard("${arrival.trainLabel} · ${arrival.directionLabel}",
                                    listOf(arrival.officialArrivalMessage,
                                        subwayArrivalDisplayStatus(arrival, displayNow))
                                        .filter(String::isNotBlank).joinToString("\n"),
                                    selected = screen.selectedArrival?.let {
                                        it.identity == arrival.identity && it.trainId == arrival.trainId
                                    } == true,
                                    enabled = !screen.liveLoading && arrival.snapshot.reliable &&
                                        displayNow - arrival.snapshot.fetchedAtEpochMillis in
                                            0L..minOf(arrival.snapshot.validForMillis, 60_000L) &&
                                        arrival.snapshot.firstArrivalAtEpochMillis > displayNow &&
                                        arrival.snapshot.trustState == ArrivalTrustState.VALID &&
                                        arrival.trainId.isNotBlank()) { onChooseArrival(arrival) }
                            }
                        }
                        PageControls(arrivalPage, screen.arrivalOptions.size, { arrivalPage = it })
                        TextButton(onClick = onRefresh, enabled = !screen.liveLoading) { Text("도착정보 새로고침") }
                    }
                }
            }
        }
        if (screen.liveLoading) LoadingStateCard("승차 장소·실시간 정보를 확인하고 있어요")
        screen.liveError?.let { NoticeCard(it, isError = configured) }
    }
}

private fun unknownReasonText(reason: AutoGuidanceUnknownReason?): String = when (reason) {
    AutoGuidanceUnknownReason.LOCATION_PERMISSION_REQUIRED -> "위치 권한이 필요해요."
    AutoGuidanceUnknownReason.LOCATION_UNAVAILABLE -> "현재 GPS 위치를 확인해 주세요."
    AutoGuidanceUnknownReason.LOCATION_INACCURATE -> "GPS 정확도가 낮아요. 위치를 다시 확인해 주세요."
    AutoGuidanceUnknownReason.LOCATION_STALE -> "현재 위치를 갱신해야 해요."
    AutoGuidanceUnknownReason.BOARDING_UNCONFIRMED -> "정확한 승차 장소·역·방향 선택이 필요해요."
    AutoGuidanceUnknownReason.ARRIVAL_FAILED -> "실시간 도착정보를 가져오지 못했어요."
    AutoGuidanceUnknownReason.ARRIVAL_UNAVAILABLE -> "실시간 정보 연결 필요"
    AutoGuidanceUnknownReason.ARRIVAL_STALE -> "도착정보가 오래됐어요. 갱신이 필요해요."
    AutoGuidanceUnknownReason.ARRIVAL_FUTURE_TIMESTAMP -> "도착정보 생성 시각이 맞지 않아 판단할 수 없어요."
    AutoGuidanceUnknownReason.ARRIVAL_INVALID_ETA -> "차량의 도착 예정시간을 확정할 수 없어요."
    AutoGuidanceUnknownReason.NEXT_ARRIVAL_UNAVAILABLE -> "현재 차량은 여유가 부족하고 다음 차량 정보가 없어요."
    AutoGuidanceUnknownReason.NO_CATCHABLE_TRAIN -> "확인된 차량들의 탑승 여유가 부족해요. 정보를 다시 확인해 주세요."
    AutoGuidanceUnknownReason.ARRIVAL_MISMATCH -> "선택한 역·방향과 도착정보가 달라요."
    AutoGuidanceUnknownReason.ARRIVAL_UNCERTAIN -> "차량 도착정보가 불확실해요."
    AutoGuidanceUnknownReason.WALKING_UNAVAILABLE -> "실제 도보 경로를 확인해야 해요."
    AutoGuidanceUnknownReason.WALKING_STALE -> "도보시간 갱신이 필요해요."
    AutoGuidanceUnknownReason.WALKING_MISMATCH -> "현재 위치·승차 장소에 맞는 도보 경로가 필요해요."
    else -> "위치·경로·실시간 정보를 확인해 주세요."
}

private fun distanceBetweenMeters(first: MapPoint, second: MapPoint): Double {
    val deltaLatitude = Math.toRadians(second.y - first.y)
    val deltaLongitude = Math.toRadians(second.x - first.x)
    val a = sin(deltaLatitude / 2).let { it * it } +
        cos(Math.toRadians(first.y)) * cos(Math.toRadians(second.y)) *
        sin(deltaLongitude / 2).let { it * it }
    return 6_371_000.0 * 2 * asin(sqrt(a.coerceIn(0.0, 1.0)))
}

private fun friendlyFailure(error: Exception, fallback: String): String =
    friendlyLocationFailure(error) ?: friendlyKakaoFailure(error)
        ?: (error as? SeoulBusApiException)?.message ?: "$fallback. 잠시 후 다시 확인해 주세요."

@Suppress("DEPRECATION")
private fun supportsVibration(context: Context): Boolean {
    val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    return vibrator?.hasVibrator() == true
}

@Suppress("DEPRECATION")
fun playVibration(
    context: Context,
    urgency: Urgency
) {

    // 긴급도에 따른 진동 횟수
    val count = when (urgency) {

        Urgency.NONE -> 0
        Urgency.LOW -> 1
        Urgency.MEDIUM -> 2
        Urgency.HIGH -> 3
        Urgency.MISSED -> 0
    }

    // 진동이 필요 없으면 종료
    if (count == 0) return

    // 기기 진동 장치 가져오기
    val vibrator: Vibrator =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {

            val manager = context.getSystemService(
                VibratorManager::class.java
            )

            manager.defaultVibrator

        } else {

            context.getSystemService(
                Context.VIBRATOR_SERVICE
            ) as Vibrator
        }

    // 진동 미지원 기기 처리
    if (!vibrator.hasVibrator()) return

    // 진동 패턴 생성
    val pattern = LongArray(count * 2) { index ->

        when {
            index == 0 -> 0L
            index % 2 == 0 -> 150L
            else -> 100L
        }
    }

    // 반복 없이 진동 실행
    val effect = VibrationEffect.createWaveform(
        pattern,
        -1
    )

    vibrator.vibrate(effect)
}
