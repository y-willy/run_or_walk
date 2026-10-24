package com.example.run_or_walk

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.LifecycleOwner
import com.example.run_or_walk.ui.theme.RUN_or_WALKTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

// The thresholds and vibration count for each state are preserved.
enum class Urgency { NONE, LOW, MEDIUM, HIGH, MISSED }

private data class LocationRequest(
    val screen: JourneyScreenState,
    val revision: Long,
    val calculateWalking: Boolean
)

private data class WalkingResult(val start: MapPoint, val route: KakaoWalkingRoute)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val activityLifecycleOwner: LifecycleOwner = this
        setContent {
            RUN_or_WALKTheme {
                PaceNudgeScreen(lifecycleOwner = activityLifecycleOwner)
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
    val transitRepository = remember { TransitRepository(BuildConfig.ODSAY_API_KEY) }
    val screens = remember {
        FeatureMode.entries.flatMap { mode ->
            SearchProvider.entries.map { provider ->
                (mode to provider) to JourneyScreenState(mode, provider)
            }
        }.toMap()
    }
    var mode by rememberSaveable { mutableStateOf(FeatureMode.ROUTE) }
    var routeProvider by rememberSaveable { mutableStateOf(SearchProvider.KAKAO) }
    var stopProvider by rememberSaveable { mutableStateOf(SearchProvider.KAKAO) }
    val provider = if (mode == FeatureMode.ROUTE) routeProvider else stopProvider
    val screen = screens.getValue(mode to provider)
    val latestScreen by rememberUpdatedState(screen)
    var currentPoint by remember { mutableStateOf<MapPoint?>(null) }
    var pendingLocation by remember { mutableStateOf<LocationRequest?>(null) }
    val vibrationSupported = remember(context) { supportsVibration(context) }

    // Every response belongs to the captured screen and request generation.
    // Changing tabs never writes into the newly visible screen's results.
    fun <T> runRequest(
        target: JourneyScreenState,
        message: String,
        failureMessage: String,
        request: suspend () -> T,
        onResult: (T) -> Unit
    ) {
        if (target.busy) return
        keyboard?.hide()
        val requestId = target.beginRequest(message)
        scope.launch {
            try {
                val result = request()
                if (target.requestId == requestId) {
                    target.loading = false
                    onResult(result)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (target.requestId == requestId) {
                    target.status = ""
                    target.error = friendlyFailure(error, failureMessage)
                }
            } finally {
                if (target.requestId == requestId) target.loading = false
            }
        }
    }

    fun searchPlaces(target: JourneyScreenState, field: SearchField) {
        if (target.busy) return
        val input = target.search(field)
        val query = input.query.trim()
        if (query.length < if (target.provider == SearchProvider.KAKAO) 1 else 2) return
        input.selected = null
        input.options = emptyList()
        input.searched = false
        target.clearRoutesAndBoarding()
        runRequest(
            target, "장소를 찾고 있어요", "장소를 검색하지 못했어요",
            request = {
                if (target.provider == SearchProvider.KAKAO) {
                    kakaoRepository.searchPlaces(query).map(JourneyPlace::from)
                } else {
                    transitRepository.searchStops(query).map(JourneyPlace::from)
                }
            },
            onResult = { places ->
                input.options = places
                input.searched = true
                target.status = if (places.isEmpty()) "" else "검색 결과에서 장소를 선택하세요."
            }
        )
    }

    fun selectPlace(target: JourneyScreenState, field: SearchField, place: JourneyPlace) {
        if (target.busy) return
        target.clearRoutesAndBoarding()
        target.search(field).apply {
            query = place.name
            selected = place
            options = emptyList()
        }
        if (field == SearchField.STOP) target.boarding = place
        target.centerOn(place.point)
        target.error = null
        target.status = place.name + " 선택 완료"
    }

    fun searchRoutes(target: JourneyScreenState) {
        if (target.busy) return
        val origin = target.origin.selected ?: return
        val destination = target.destination.selected ?: return
        target.clearRoutesAndBoarding()
        runRequest(
            target, "대중교통 경로를 찾고 있어요", "경로를 검색하지 못했어요",
            request = {
                if (target.provider == SearchProvider.KAKAO) {
                    kakaoRepository.searchPublicTransit(origin.point, destination.point)
                        .mapIndexed { index, route ->
                            JourneyTransitRoute(id = "kakao-$index", kakao = route)
                        }
                } else {
                    transitRepository.searchTransitRoutes(
                        requireNotNull(origin.transitStop), requireNotNull(destination.transitStop)
                    ).mapIndexed { index, route ->
                        JourneyTransitRoute(id = "odsay-$index", odsay = route)
                    }
                }
            },
            onResult = { routes ->
                target.routes = routes
                target.routeSearchCompleted = true
                target.status = if (routes.isEmpty()) "" else "경로를 선택해 첫 승차 장소를 확인하세요."
            }
        )
    }

    fun calculateWalking(target: JourneyScreenState) {
        if (target.busy) return
        val boarding = target.boarding ?: return
        target.clearWalking()
        runRequest(
            target, "현재 위치와 도보 경로를 확인하고 있어요", "도보 경로를 찾지 못했어요",
            request = {
                val start = getCurrentPoint(context)
                val route = kakaoRepository.searchWalk(start, boarding.point)
                require(route.distanceMeters >= 0 && route.durationSeconds >= 0) {
                    "도보 경로 정보가 올바르지 않습니다."
                }
                WalkingResult(start, route)
            },
            onResult = { result ->
                currentPoint = result.start
                target.walkRoute = WalkRoute(result.route.distanceMeters, result.route.durationSeconds)
                target.walkingPoints = result.route.points
                target.centerOn(result.start)
                target.status = "도보 경로를 확인했어요. 도착시간을 입력해 이동 안내를 확인하세요."
            }
        )
    }

    fun refreshLocation(target: JourneyScreenState) {
        if (target.busy) return
        target.clearWalking()
        runRequest(
            target, "현재 위치를 확인하고 있어요", "현재 위치를 가져오지 못했어요",
            request = { getCurrentPoint(context) },
            onResult = { point ->
                currentPoint = point
                target.centerOn(point)
                target.status = if (target.boarding != null) {
                    "현재 위치를 업데이트했어요. 도보 경로를 다시 계산하세요."
                } else "현재 위치를 확인했어요."
            }
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val pending = pendingLocation
        pendingLocation = null
        if (pending != null) {
            val target = pending.screen
            target.waitingForPermission = false
            val granted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
            if (target.revision == pending.revision) {
                when {
                    !granted -> {
                        target.status = ""
                        target.error = "위치 권한이 필요해요. 앱 권한 설정에서 위치를 허용한 뒤 다시 시도해 주세요."
                    }
                    latestScreen !== target -> {
                        target.status = "위치 권한을 허용했어요. 이 화면에서 도보 계산이나 현재 위치를 다시 눌러주세요."
                    }
                    pending.calculateWalking -> calculateWalking(target)
                    else -> refreshLocation(target)
                }
            }
        }
    }

    fun requestLocation(target: JourneyScreenState, walking: Boolean) {
        if (target.busy) return
        if (pendingLocation != null) {
            target.error = "진행 중인 위치 권한 요청을 먼저 완료해 주세요."
            return
        }
        if (hasLocationPermission(context)) {
            if (walking) calculateWalking(target) else refreshLocation(target)
        } else {
            keyboard?.hide()
            target.error = null
            target.waitingForPermission = true
            target.status = "위치 권한을 허용해 주세요."
            pendingLocation = LocationRequest(target, target.revision, walking)
            permissionLauncher.launch(arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ))
        }
    }

    fun selectRoute(target: JourneyScreenState, route: JourneyTransitRoute) {
        if (target.busy) return
        target.clearWalking()
        target.selectedRoute = route
        target.boardingCandidates = emptyList()
        target.candidatesSearched = false
        target.boardingChosenByUser = false
        val kakao = route.kakao
        if (kakao != null) {
            target.boardingSource = kakao.boardingSource
            // Preserve the existing explicit, warned fallback for an unknown point.
            val point = kakao.boardingPoint ?: target.origin.selected?.point
            target.boarding = point?.let {
                JourneyPlace("boarding-${route.id}", route.boardingName, route.serviceName, it)
            }
        } else {
            target.boardingSource = null
            target.boarding = route.odsay?.boardingStop?.let(JourneyPlace::from)
        }
        target.boarding?.let { target.centerOn(it.point) }
        target.status = "첫 승차 장소를 선택했어요."
        target.error = null
        // The user chose the route explicitly; never choose a route or stop automatically.
        requestLocation(target, walking = true)
    }

    fun searchBoardingCandidates(target: JourneyScreenState) {
        val route = target.selectedRoute?.kakao ?: return
        if (target.busy) return
        if (route.boardingStopName.isBlank()) {
            target.error = "경로에 승차 장소 이름이 없어요. 출발지를 다시 선택해 경로를 검색해 주세요."
            return
        }
        target.boardingCandidates = emptyList()
        target.candidatesSearched = false
        runRequest(
            target, "승차 장소·출입구 후보를 찾고 있어요", "승차 장소 후보를 검색하지 못했어요",
            request = { kakaoRepository.searchPlaces(route.boardingStopName).map(JourneyPlace::from) },
            onResult = { places ->
                target.boardingCandidates = places
                target.candidatesSearched = true
                target.status = if (places.isEmpty()) "" else "실제 승차할 장소·출입구를 직접 선택하세요."
            }
        )
    }

    fun confirmBoarding(target: JourneyScreenState, place: JourneyPlace) {
        if (target.busy) return
        val selected = target.selectedRoute ?: return
        val kakao = selected.kakao ?: return
        target.clearWalking()
        target.selectedRoute = selected.copy(kakao = kakao.copy(
            boardingPoint = place.point, boardingSource = BoardingPointSource.VERIFIED
        ))
        target.boarding = place
        target.boardingSource = BoardingPointSource.VERIFIED
        target.boardingChosenByUser = true
        target.boardingCandidates = emptyList()
        target.candidatesSearched = false
        target.centerOn(place.point)
        target.error = null
        target.status = "직접 선택한 승차 위치로 도보 경로를 다시 계산해요."
        requestLocation(target, walking = true)
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        // Header and tabs scroll too, leaving usable input space on small screens
        // and in landscape when the IME is open.
        Column(
            modifier = Modifier.fillMaxSize().safeDrawingPadding().imePadding()
                .verticalScroll(screen.scrollState)
                .padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                PaceNudgeHeader()
                FeatureModeSelector(
                    selectedIndex = mode.ordinal,
                    onSelect = { index -> keyboard?.hide(); mode = FeatureMode.entries[index] }
                )
            }
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = provider == SearchProvider.KAKAO,
                        onClick = {
                            keyboard?.hide()
                            if (mode == FeatureMode.ROUTE) routeProvider = SearchProvider.KAKAO
                            else stopProvider = SearchProvider.KAKAO
                        },
                        label = { Text("카카오") },
                        modifier = Modifier.heightIn(min = 48.dp)
                    )
                    FilterChip(
                        selected = provider == SearchProvider.ODSAY,
                        onClick = {
                            keyboard?.hide()
                            if (mode == FeatureMode.ROUTE) routeProvider = SearchProvider.ODSAY
                            else stopProvider = SearchProvider.ODSAY
                        },
                        label = { Text("ODsay") },
                        modifier = Modifier.heightIn(min = 48.dp)
                    )
                }

                // Animate only the search/results subtree. The map is a stable sibling.
                AnimatedContent(
                    targetState = screen,
                    transitionSpec = {
                        (fadeIn(tween(220)) + slideInVertically(tween(220)) { it / 24 })
                            .togetherWith(fadeOut(tween(180)))
                    },
                    label = "검색 모드 전환"
                ) { target ->
                    SearchAndRoutesContent(
                        screen = target,
                        onSearch = { searchPlaces(target, it) },
                        onEdit = { field, value -> target.editQuery(field, value) },
                        onChoosePlace = { field, place -> selectPlace(target, field, place) },
                        onSearchRoutes = { searchRoutes(target) },
                        onChooseRoute = { selectRoute(target, it) }
                    )
                }

                if (screen.busy) LoadingStateCard(screen.status)
                screen.error?.let { error ->
                    Column(
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                    ) {
                        NoticeCard(message = error, isError = true)
                        if (error.contains("위치 권한")) {
                            TextButton(onClick = {
                                context.startActivity(Intent(
                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.parse("package:" + context.packageName)
                                ))
                            }) { Text("앱 권한 설정 열기") }
                        }
                    }
                }

                screen.boarding?.let { boarding ->
                    BoardingStopCard(
                        name = boarding.name,
                        detail = boarding.detail.ifBlank { "선택한 첫 승차 장소" },
                        notice = screen.boardingNotice
                    )
                }
                if (screen.selectedRoute?.kakao != null &&
                    screen.boardingSource != BoardingPointSource.VERIFIED
                ) {
                    SectionCard(
                        title = "승차 위치를 확인해 주세요",
                        subtitle = "추정 위치만으로 정확한 승강장·출입구를 알 수 없어요."
                    ) {
                        OutlinedButton(
                            onClick = { searchBoardingCandidates(screen) },
                            enabled = !screen.busy,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        ) { Text("승차 장소·출입구 후보 검색") }
                        screen.boardingCandidates.forEach { place ->
                            key(place.id) {
                                PlaceOptionCard(place.name, place.detail, enabled = !screen.busy) {
                                    confirmBoarding(screen, place)
                                }
                            }
                        }
                        if (screen.candidatesSearched && screen.boardingCandidates.isEmpty() && !screen.busy) {
                            EmptyStateCard("승차 후보가 없어요", "출발지를 더 구체적으로 선택해 경로를 다시 검색해 주세요.")
                        }
                    }
                }

                SectionCard(title = "승차 지점까지", subtitle = "현재 위치에서 이어지는 도보 경로") {
                    // Keep this MapView outside AnimatedContent and unkeyed by mode/provider.
                    KakaoMapPanel(
                        lifecycleOwner = lifecycleOwner,
                        center = screen.mapCenter,
                        routePoints = screen.walkingPoints,
                        modifier = Modifier.fillMaxWidth().height(300.dp),
                        currentPoint = currentPoint,
                        boardingPoint = screen.boarding?.point,
                        recenterRequest = screen.recenterRequest,
                        onCurrentLocation = { requestLocation(screen, walking = false) }
                    )
                    Button(
                        onClick = { requestLocation(screen, walking = true) },
                        enabled = screen.boarding != null && !screen.busy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
                    ) { Text("현재 위치에서 도보 경로 계산") }
                    if (screen.boarding == null) {
                        Text(
                            "승차할 장소를 먼저 선택해 주세요.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                // The request loader above names the actual in-flight operation.
                WalkingSummaryCard(route = screen.walkRoute, loading = false)
                if (screen.walkRoute != null && screen.walkingPoints.size < 2) {
                    NoticeCard("도보 요약은 있지만 지도에 표시할 경로 좌표가 없어요. 실제 이동 경로를 확인해 주세요.")
                }

                SectionCard(title = "지금, 어떻게 이동할까요?", subtitle = "도착시간과 여유시간을 입력해 주세요. · 수동 입력") {
                    TimeInput(
                        value = screen.arrivalMinutes,
                        label = "차량 도착까지 (분) · 수동 입력",
                        enabled = !screen.busy,
                        onChange = {
                            screen.arrivalMinutes = it
                            screen.assessment = null
                            screen.timeInputError = null
                        }
                    )
                    TimeInput(
                        value = screen.bufferMinutes,
                        label = "안전 여유시간 (분)",
                        enabled = !screen.busy,
                        onChange = {
                            screen.bufferMinutes = it
                            screen.assessment = null
                            screen.timeInputError = null
                        }
                    )
                    screen.timeInputError?.let { NoticeCard(it, isError = true) }
                    Button(
                        enabled = screen.walkRoute != null && !screen.busy,
                        onClick = {
                            keyboard?.hide()
                            val arrival = screen.arrivalMinutes.replace(',', '.').toDoubleOrNull()
                            val buffer = screen.bufferMinutes.replace(',', '.').toDoubleOrNull()
                            val route = screen.walkRoute
                            if (arrival == null || buffer == null || route == null) {
                                screen.timeInputError = "시간은 0 이상의 숫자로 입력해 주세요."
                            } else {
                                try {
                                    screen.assessment = calculateUrgency(arrival, buffer, route.durationSeconds)
                                    screen.timeInputError = null
                                    if (vibrationSupported) playVibration(context, screen.assessment!!.urgency)
                                } catch (_: IllegalArgumentException) {
                                    screen.assessment = null
                                    screen.timeInputError = "시간은 0 이상의 유효한 숫자로 입력해 주세요."
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
                    ) { Text("이동 안내 확인 · 진동") }
                    if (screen.walkRoute == null) {
                        Text(
                            "도보 경로를 계산하면 이동 안내를 확인할 수 있어요.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                screen.assessment?.let { result ->
                    UrgencyResultCard(
                        urgency = result.urgency,
                        arrivalMinutes = result.arrivalMinutes,
                        walkingMinutes = result.walkingMinutes,
                        bufferMinutes = result.bufferMinutes,
                        slackMinutes = result.slackMinutes,
                        vibrationSupported = vibrationSupported
                    )
                }
                if (!screen.busy && screen.error == null && screen.status.isNotBlank()) {
                    Text(
                        text = screen.status,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                    )
                }
                Text(
                    "차량 도착시간은 수동 입력이며 실시간 정보가 아니에요. 도보시간은 마지막 계산 시점의 예상이며, 역 내부 이동과 승강장 위치는 별도로 확인해 주세요.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SearchAndRoutesContent(
    screen: JourneyScreenState,
    onSearch: (SearchField) -> Unit,
    onEdit: (SearchField, String) -> Unit,
    onChoosePlace: (SearchField, JourneyPlace) -> Unit,
    onSearchRoutes: () -> Unit,
    onChooseRoute: (JourneyTransitRoute) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        val minimumLength = if (screen.provider == SearchProvider.KAKAO) 1 else 2
        if (screen.mode == FeatureMode.ROUTE) {
            SectionCard(title = "어디로 이동하세요?", subtitle = "출발지와 도착지를 선택하면 첫 승차 장소를 찾아드려요.") {
                LocationSearchInput(
                    label = "출발지", placeholder = "예: 서울역", input = screen.origin,
                    enabled = !screen.busy, minimumLength = minimumLength,
                    onChange = { onEdit(SearchField.ORIGIN, it) },
                    onSearch = { onSearch(SearchField.ORIGIN) },
                    onChoose = { onChoosePlace(SearchField.ORIGIN, it) }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                LocationSearchInput(
                    label = "도착지", placeholder = "예: 동탄역", input = screen.destination,
                    enabled = !screen.busy, minimumLength = minimumLength,
                    onChange = { onEdit(SearchField.DESTINATION, it) },
                    onSearch = { onSearch(SearchField.DESTINATION) },
                    onChoose = { onChoosePlace(SearchField.DESTINATION, it) }
                )
                Button(
                    onClick = onSearchRoutes,
                    enabled = screen.origin.selected != null && screen.destination.selected != null && !screen.busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
                ) { Text("대중교통 경로 찾기") }
            }
            if (screen.routes.isNotEmpty()) {
                Text("이동 경로", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                screen.routes.forEach { route ->
                    key(route.id) {
                        TransitRouteCard(
                            durationMinutes = route.durationMinutes,
                            serviceName = route.serviceName.ifBlank { "노선 정보 없음" },
                            boardingName = route.boardingName,
                            detail = route.detail,
                            selected = screen.selectedRoute?.id == route.id,
                            enabled = !screen.busy,
                            onSelect = { if (!screen.busy) onChooseRoute(route) }
                        )
                    }
                }
            } else if (screen.routeSearchCompleted && !screen.busy && screen.error == null) {
                EmptyStateCard("검색된 경로가 없어요", "출발지나 도착지를 바꾸어 다시 검색해 주세요.")
            }
        } else {
            SectionCard(title = "어디서 탑승하세요?", subtitle = "정류장·역을 검색하고 승차할 장소를 선택하세요.") {
                LocationSearchInput(
                    label = "정류장·역 이름", placeholder = "예: 강남역", input = screen.stop,
                    enabled = !screen.busy, minimumLength = minimumLength,
                    onChange = { onEdit(SearchField.STOP, it) },
                    onSearch = { onSearch(SearchField.STOP) },
                    onChoose = { onChoosePlace(SearchField.STOP, it) }
                )
            }
        }
    }
}

@Composable
private fun LocationSearchInput(
    label: String,
    placeholder: String,
    input: JourneySearchState,
    enabled: Boolean,
    minimumLength: Int,
    onChange: (String) -> Unit,
    onSearch: () -> Unit,
    onChoose: (JourneyPlace) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val canSearch = enabled && input.query.trim().length >= minimumLength
        OutlinedTextField(
            value = input.query,
            onValueChange = onChange,
            enabled = enabled,
            label = { Text(label) },
            placeholder = { Text(placeholder) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { if (canSearch) onSearch() }),
            trailingIcon = {
                TextButton(
                    onClick = onSearch,
                    enabled = canSearch,
                    modifier = Modifier.heightIn(min = 48.dp)
                ) { Text("검색") }
            },
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth()
        )
        input.selected?.let { selected ->
            PlaceOptionCard(selected.name, selected.detail, selected = true, enabled = enabled) {
                if (enabled) onChoose(selected)
            }
        }
        AnimatedVisibility(visible = input.options.isNotEmpty(), enter = fadeIn(tween(180)), exit = fadeOut(tween(160))) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "검색 결과 " + input.options.size + "개 · 장소를 선택하세요",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                input.options.forEach { place ->
                    key(place.id) {
                        PlaceOptionCard(place.name, place.detail, enabled = enabled) {
                            if (enabled) onChoose(place)
                        }
                    }
                }
            }
        }
        if (input.searched && input.options.isEmpty() && input.selected == null && enabled) {
            EmptyStateCard("검색 결과가 없어요", "이름을 확인하거나 더 구체적인 장소명으로 검색해 주세요.")
        }
    }
}

@Composable
private fun TimeInput(value: String, label: String, enabled: Boolean, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth()
    )
}

private fun friendlyFailure(error: Exception, fallback: String): String = when {
    error is SecurityException -> "위치 권한이 필요해요. 앱 권한 설정에서 위치를 허용한 뒤 다시 시도해 주세요."
    error.message.orEmpty().contains("키") -> "API 설정을 확인할 수 없어요. 앱의 API 키 설정을 확인해 주세요."
    error.message.orEmpty().contains("GPS") -> "현재 위치를 가져오지 못했어요. 기기의 위치 서비스를 켠 뒤 다시 시도해 주세요."
    else -> "$fallback. 네트워크 연결과 선택한 장소를 확인한 뒤 다시 시도해 주세요."
}

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
