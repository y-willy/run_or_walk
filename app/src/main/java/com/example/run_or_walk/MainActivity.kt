package com.example.run_or_walk

import android.Manifest
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll

import androidx.compose.material3.*
import androidx.compose.runtime.*

import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

import androidx.lifecycle.LifecycleOwner

import kotlinx.coroutines.launch

// 긴급도 단계
enum class Urgency {
    NONE,
    LOW,
    MEDIUM,
    HIGH,
    MISSED
}

// 어떤 장소까지 도보 경로를 계산할지 구분
enum class WalkTarget {
    KAKAO_PLACE,
    TRANSIT_STOP,
    KAKAO_TRANSIT
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Activity 생명주기 객체를 화면에 전달
        val activityLifecycleOwner: LifecycleOwner = this

        setContent {
            MaterialTheme {
                PaceNudgeScreen(
                    lifecycleOwner = activityLifecycleOwner
                )
            }
        }
    }
}

@Composable
fun PaceNudgeScreen(
    lifecycleOwner: LifecycleOwner
) {

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 카카오 API 객체
    val kakaoRepository = remember {
        KakaoApiRepository(BuildConfig.KAKAO_REST_KEY)
    }

    // 기존 ODsay API 객체
    val repository = remember {
        TransitRepository(BuildConfig.ODSAY_API_KEY)
    }

    // -----------------------------
    // 카카오 장소 검색 상태
    // -----------------------------

    var kakaoQuery by remember {
        mutableStateOf("서울역")
    }

    var kakaoPlaces by remember {
        mutableStateOf<List<KakaoPlace>>(emptyList())
    }

    // 선택한 카카오 장소
    var selectedPlace by remember {
        mutableStateOf<KakaoPlace?>(null)
    }

    var kakaoStatus by remember {
        mutableStateOf("장소를 검색하세요.")
    }

    // 지도 중심 좌표
    var mapCenter by remember {
        mutableStateOf<MapPoint?>(null)
    }

    // 지도에 표시할 실제 도보 경로
    var kakaoWalkRoute by remember {
        mutableStateOf<KakaoWalkingRoute?>(null)
    }

    // -----------------------------
    // 카카오 대중교통 경로 검색 상태
    // -----------------------------

    var useKakaoTransitApi by remember {
        mutableStateOf(true)
    }

    var kakaoStartQuery by remember {
        mutableStateOf("서울역")
    }

    var kakaoStartPlaces by remember {
        mutableStateOf<List<KakaoPlace>>(emptyList())
    }

    var selectedKakaoStartPlace by remember {
        mutableStateOf<KakaoPlace?>(null)
    }

    var kakaoEndQuery by remember {
        mutableStateOf("동탄역")
    }

    var kakaoEndPlaces by remember {
        mutableStateOf<List<KakaoPlace>>(emptyList())
    }

    var selectedKakaoEndPlace by remember {
        mutableStateOf<KakaoPlace?>(null)
    }

    var kakaoTransitChoices by remember {
        mutableStateOf<List<KakaoTransitChoice>>(emptyList())
    }

    var selectedKakaoTransit by remember {
        mutableStateOf<KakaoTransitChoice?>(null)
    }

    var kakaoBoardingNotice by remember {
        mutableStateOf("")
    }

    // 승차 지점 후보 목록 (추정/미확인 위치 시 직접 선택)
    var candidateBoardingPlaces by remember {
        mutableStateOf<List<KakaoPlace>>(emptyList())
    }

    // -----------------------------
    // ODsay 정류장·경로 검색 상태
    // -----------------------------

    // false: 정류장 검색
    // true: 출발역 → 도착역 검색
    var routeMode by remember {
        mutableStateOf(false)
    }

    var stopQuery by remember {
        mutableStateOf("")
    }

    var stopResults by remember {
        mutableStateOf<List<TransitStop>>(emptyList())
    }

    var startQuery by remember {
        mutableStateOf("")
    }

    var startResults by remember {
        mutableStateOf<List<TransitStop>>(emptyList())
    }

    var selectedStart by remember {
        mutableStateOf<TransitStop?>(null)
    }

    var endQuery by remember {
        mutableStateOf("")
    }

    var endResults by remember {
        mutableStateOf<List<TransitStop>>(emptyList())
    }

    var selectedEnd by remember {
        mutableStateOf<TransitStop?>(null)
    }

    // 대중교통 경로 검색 결과
    var transitPlans by remember {
        mutableStateOf<List<TransitPlan>>(emptyList())
    }

    // 선택된 승차 지점
    var boardingStop by remember {
        mutableStateOf<TransitStop?>(null)
    }

    // -----------------------------
    // GPS 및 도보 경로 상태
    // -----------------------------

    var currentPoint by remember {
        mutableStateOf<MapPoint?>(null)
    }

    // 긴급도 계산에 사용할 도보 결과
    var walkRoute by remember {
        mutableStateOf<WalkRoute?>(null)
    }

    // 차량 도착까지 남은 시간
    var arrivalMinutes by remember {
        mutableStateOf("5")
    }

    // 안전 여유시간
    var bufferMinutes by remember {
        mutableStateOf("2")
    }

    var status by remember {
        mutableStateOf("검색 모드를 선택하세요.")
    }

    var urgencyResult by remember {
        mutableStateOf("아직 긴급도를 계산하지 않았습니다.")
    }

    var loading by remember {
        mutableStateOf(false)
    }

    // 위치 권한을 얻은 뒤 실행할 도보 검색 종류
    var pendingWalkTarget by remember {
        mutableStateOf(WalkTarget.TRANSIT_STOP)
    }

    // -----------------------------
    // 승차 지점 선택
    // -----------------------------

    fun selectBoardingStop(stop: TransitStop) {

        boardingStop = stop
        selectedKakaoTransit = null
        candidateBoardingPlaces = emptyList()

        // 이전 도보 경로 초기화
        walkRoute = null
        kakaoWalkRoute = null

        // 선택 지점으로 지도 이동
        mapCenter = stop.point

        urgencyResult = "도보시간을 먼저 계산하세요."
        status = "${stop.name} 선택 완료"
    }

    // -----------------------------
    // 카카오 도보 경로 계산
    // -----------------------------

    fun calculateWalk(target: WalkTarget) {

        kakaoBoardingNotice = ""

        var destinationPoint: MapPoint? = null
        var destinationName: String? = null

        when (target) {
            WalkTarget.KAKAO_PLACE -> {
                destinationPoint = selectedPlace?.point
                destinationName = selectedPlace?.name
            }

            WalkTarget.TRANSIT_STOP -> {
                destinationPoint = boardingStop?.point
                destinationName = boardingStop?.name
            }

            WalkTarget.KAKAO_TRANSIT -> {
                val choice = selectedKakaoTransit
                if (choice != null) {
                    destinationName = "${choice.boardingStopName} (${choice.serviceName})"

                    if (choice.boardingPoint != null) {
                        destinationPoint = choice.boardingPoint
                        when (choice.boardingSource) {
                            BoardingPointSource.VERIFIED -> {
                                kakaoBoardingNotice = "✓ 검증된 승차 위치 기준"
                            }
                            BoardingPointSource.ESTIMATED -> {
                                kakaoBoardingNotice = "⚠️ 경로 추정 좌표 기준 (지도에서 실제 승차/출입구 위치를 확인해 주세요)"
                            }
                            BoardingPointSource.UNKNOWN -> {
                                kakaoBoardingNotice = "⚠️ 승차 위치 미확인 (아래 후보 목록에서 정확한 승차 장소를 선택해 주세요)"
                            }
                        }
                    } else {
                        kakaoBoardingNotice = "⚠️ 승차 위치 미확인 (아래 후보 목록에서 정확한 승차 장소를 선택해 주세요)"
                        destinationPoint = selectedKakaoStartPlace?.point
                    }
                }
            }
        }

        if (destinationPoint == null) {
            status = "먼저 이동할 장소 또는 대중교통 경로를 선택하세요."
            return
        }

        scope.launch {

            loading = true

            walkRoute = null
            kakaoWalkRoute = null

            try {

                status = "현재 GPS 위치 확인 중..."

                // 스마트폰 현재 위치 가져오기
                val point = getCurrentPoint(context)

                currentPoint = point

                status = "$destinationName 도보 경로 검색 중..."

                // 카카오 도보 길찾기 API 호출
                val route = kakaoRepository.searchWalk(
                    start = point,
                    end = destinationPoint
                )

                // 지도에 표시할 경로 저장
                kakaoWalkRoute = route

                // 기존 긴급도 계산 구조와 연결
                walkRoute = WalkRoute(
                    distanceMeters = route.distanceMeters,
                    durationSeconds = route.durationSeconds
                )

                // 지도 중심을 현재 위치로 변경
                mapCenter = point

                val walkingMinutes =
                    route.durationSeconds / 60.0

                val sourceTag = when (selectedKakaoTransit?.boardingSource) {
                    BoardingPointSource.VERIFIED -> " [확정 승차지점]"
                    BoardingPointSource.ESTIMATED -> " [경로 추정 위치 - 확인 권장]"
                    BoardingPointSource.UNKNOWN, null -> " [위치 미확인]"
                }

                val message = """
                    목적지: $destinationName
                    도보 거리: ${route.distanceMeters}m
                    예상 도보시간: ${"%.1f".format(walkingMinutes)}분$sourceTag
                """.trimIndent()

                status = "도보 경로 계산 완료"

                kakaoStatus = message

                urgencyResult = "긴급도를 다시 계산하세요."

            } catch (e: Exception) {

                status = "도보 경로 오류: ${e.message}"

                kakaoStatus = status

            } finally {

                loading = false
            }
        }
    }

    // -----------------------------
    // GPS 위치 권한 요청
    // -----------------------------

    val locationPermissionLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->

            val granted =
                permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                        permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true

            if (granted) {

                // 권한 획득 후 요청했던 도보 계산 재개
                calculateWalk(pendingWalkTarget)

            } else {

                status = "위치 권한이 필요합니다."
                kakaoStatus = status
            }
        }

    // 도보 경로 계산 전 위치 권한 확인
    fun requestWalk(target: WalkTarget) {

        if (loading) return

        if (hasLocationPermission(context)) {

            calculateWalk(target)

        } else {

            pendingWalkTarget = target

            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    // -----------------------------
    // 메인 화면
    // -----------------------------

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),

        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {

        Text(
            text = "PaceNudge",
            style = MaterialTheme.typography.headlineMedium
        )

        Text(
            "대중교통을 놓치지 않도록 이동 속도를 안내합니다."
        )

        // -----------------------------
        // 카카오맵 지도
        // -----------------------------

        KakaoMapPanel(
            lifecycleOwner = lifecycleOwner,

            // 선택 장소 또는 현위치
            center = mapCenter,

            // 실제 도보 경로
            routePoints = kakaoWalkRoute?.points.orEmpty(),

            modifier = Modifier
                .fillMaxWidth()
                .height(300.dp)
        )

        HorizontalDivider()

        // -----------------------------
        // 카카오 장소 검색
        // -----------------------------

        Text(
            "카카오 장소 검색",
            style = MaterialTheme.typography.titleLarge
        )

        OutlinedTextField(
            value = kakaoQuery,

            onValueChange = {
                kakaoQuery = it
            },

            label = {
                Text("역 또는 정류장 검색")
            },

            placeholder = {
                Text("예: 서울역")
            },

            modifier = Modifier.fillMaxWidth()
        )

        Button(
            enabled = !loading && kakaoQuery.isNotBlank(),

            onClick = {

                scope.launch {

                    loading = true
                    kakaoStatus = "장소 검색 중..."

                    try {

                        kakaoPlaces =
                            kakaoRepository.searchPlaces(
                                kakaoQuery
                            )

                        // 이전 선택 초기화
                        selectedPlace = null
                        kakaoWalkRoute = null
                        walkRoute = null

                        kakaoStatus =
                            "검색 결과 ${kakaoPlaces.size}개"

                    } catch (e: Exception) {

                        kakaoPlaces = emptyList()

                        kakaoStatus =
                            "검색 실패: ${e.message}"

                    } finally {

                        loading = false
                    }
                }
            }
        ) {
            Text("카카오 장소 검색")
        }

        // 검색된 장소 목록
        kakaoPlaces.take(8).forEach { place ->

            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),

                onClick = {

                    selectedPlace = place

                    // 선택한 장소로 지도 이동
                    mapCenter = place.point

                    // 이전 경로 삭제
                    kakaoWalkRoute = null
                    walkRoute = null

                    kakaoStatus =
                        "${place.name} 선택 완료"
                }
            ) {

                Text(
                    "${place.name}\n${place.address}"
                )
            }
        }

        // 선택된 장소 표시
        Text(
            "선택된 장소: ${selectedPlace?.name ?: "없음"}"
        )

        // 현재 위치부터 선택 장소까지 도보 경로
        Button(
            enabled = selectedPlace != null && !loading,

            onClick = {
                requestWalk(WalkTarget.KAKAO_PLACE)
            }
        ) {
            Text("현위치 → 선택한 장소 도보 길찾기")
        }

        Text(
            text = kakaoStatus,
            style = MaterialTheme.typography.bodyMedium
        )

        HorizontalDivider()

        // -----------------------------
        // 대중교통 검색 모드 선택
        // -----------------------------

        Text(
            "대중교통 검색",
            style = MaterialTheme.typography.titleLarge
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {

            Button(
                enabled = !loading,

                onClick = {

                    routeMode = false

                    boardingStop = null
                    walkRoute = null
                    kakaoWalkRoute = null

                    status = "정류장 검색 모드"
                }
            ) {
                Text("정류장 검색")
            }

            Button(
                enabled = !loading,

                onClick = {

                    routeMode = true

                    boardingStop = null
                    walkRoute = null
                    kakaoWalkRoute = null

                    status = "경로 검색 모드"
                }
            ) {
                Text("경로 검색")
            }
        }

        HorizontalDivider()

        if (!routeMode) {

            // -----------------------------
            // 기능 2: 정류장 단독 검색
            // -----------------------------

            Text(
                "기능 2. 정류장·역 검색",
                style = MaterialTheme.typography.titleLarge
            )

            OutlinedTextField(
                value = stopQuery,

                onValueChange = {
                    stopQuery = it
                },

                label = {
                    Text("정류장 또는 역 이름")
                },

                placeholder = {
                    Text("예: 강남역")
                },

                modifier = Modifier.fillMaxWidth()
            )

            Button(
                enabled = !loading && stopQuery.length >= 2,

                onClick = {

                    scope.launch {

                        loading = true

                        try {

                            status = "정류장 검색 중..."

                            stopResults =
                                repository.searchStops(stopQuery)

                            status =
                                "${stopResults.size}개 검색 결과"

                        } catch (e: Exception) {

                            stopResults = emptyList()

                            status =
                                "검색 오류: ${e.message}"

                        } finally {

                            loading = false
                        }
                    }
                }
            ) {
                Text("정류장 검색")
            }

            // 정류장 선택
            StopOptions(stopResults) { stop ->

                selectBoardingStop(stop)
            }

        } else {

            // -----------------------------
            // 기능 1: 출발역 → 도착역
            // -----------------------------

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = useKakaoTransitApi,
                    onClick = { useKakaoTransitApi = true },
                    label = { Text("카카오 대중교통") }
                )
                FilterChip(
                    selected = !useKakaoTransitApi,
                    onClick = { useKakaoTransitApi = false },
                    label = { Text("ODsay 대중교통") }
                )
            }

            if (useKakaoTransitApi) {

                Text(
                    "기능 1. 출발역 → 도착역 (카카오 API)",
                    style = MaterialTheme.typography.titleLarge
                )

                // 출발역 검색
                OutlinedTextField(
                    value = kakaoStartQuery,
                    onValueChange = { kakaoStartQuery = it },
                    label = { Text("출발지 (역 또는 장소)") },
                    placeholder = { Text("예: 서울역") },
                    modifier = Modifier.fillMaxWidth()
                )

                Button(
                    enabled = !loading && kakaoStartQuery.isNotBlank(),
                    onClick = {
                        scope.launch {
                            loading = true
                            status = "출발지 검색 중..."
                            try {
                                kakaoStartPlaces = kakaoRepository.searchPlaces(kakaoStartQuery)
                                status = "출발지를 선택하세요."
                            } catch (e: Exception) {
                                kakaoStartPlaces = emptyList()
                                status = "출발지 검색 실패: ${e.message}"
                            } finally {
                                loading = false
                            }
                        }
                    }
                ) {
                    Text("출발지 검색")
                }

                kakaoStartPlaces.take(5).forEach { place ->
                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            selectedKakaoStartPlace = place
                            kakaoStartPlaces = emptyList()
                            kakaoTransitChoices = emptyList()
                            selectedKakaoTransit = null
                            status = "출발지: ${place.name}"
                        }
                    ) {
                        Text("${place.name}\n${place.address}")
                    }
                }

                Text("선택된 출발지: ${selectedKakaoStartPlace?.name ?: "없음"}")

                // 도착역 검색
                OutlinedTextField(
                    value = kakaoEndQuery,
                    onValueChange = { kakaoEndQuery = it },
                    label = { Text("도착지 (역 또는 장소)") },
                    placeholder = { Text("예: 동탄역") },
                    modifier = Modifier.fillMaxWidth()
                )

                Button(
                    enabled = !loading && kakaoEndQuery.isNotBlank(),
                    onClick = {
                        scope.launch {
                            loading = true
                            status = "도착지 검색 중..."
                            try {
                                kakaoEndPlaces = kakaoRepository.searchPlaces(kakaoEndQuery)
                                status = "도착지를 선택하세요."
                            } catch (e: Exception) {
                                kakaoEndPlaces = emptyList()
                                status = "도착지 검색 실패: ${e.message}"
                            } finally {
                                loading = false
                            }
                        }
                    }
                ) {
                    Text("도착지 검색")
                }

                kakaoEndPlaces.take(5).forEach { place ->
                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            selectedKakaoEndPlace = place
                            kakaoEndPlaces = emptyList()
                            kakaoTransitChoices = emptyList()
                            selectedKakaoTransit = null
                            status = "도착지: ${place.name}"
                        }
                    ) {
                        Text("${place.name}\n${place.address}")
                    }
                }

                Text("선택된 도착지: ${selectedKakaoEndPlace?.name ?: "없음"}")

                // 카카오 대중교통 경로 검색
                Button(
                    enabled = !loading && selectedKakaoStartPlace != null && selectedKakaoEndPlace != null,
                    onClick = {
                        val start = selectedKakaoStartPlace
                        val end = selectedKakaoEndPlace

                        if (start != null && end != null) {
                            scope.launch {
                                loading = true
                                status = "카카오 대중교통 경로 검색 중..."
                                try {
                                    kakaoTransitChoices = kakaoRepository.searchPublicTransit(
                                        start = start.point,
                                        end = end.point
                                    )
                                    status = if (kakaoTransitChoices.isEmpty()) {
                                        "검색된 카카오 대중교통 경로가 없습니다."
                                    } else {
                                        "경로를 선택하세요."
                                    }
                                } catch (e: Exception) {
                                    kakaoTransitChoices = emptyList()
                                    status = "경로 검색 오류: ${e.message}"
                                } finally {
                                    loading = false
                                }
                            }
                        }
                    }
                ) {
                    Text("카카오 대중교통 경로 찾기")
                }

                // 카카오 대중교통 경로 목록
                kakaoTransitChoices.forEachIndexed { index, choice ->
                    val totalMinutes = choice.totalSeconds / 60
                    val totalSecsRem = choice.totalSeconds % 60
                    val timeFormatted = if (totalSecsRem > 0) "${totalMinutes}분 ${totalSecsRem}초" else "${totalMinutes}분"

                    val typeLabel = if (choice.firstTransitType.isNotBlank()) "[${choice.firstTransitType}] " else ""
                    val sourceBadge = when (choice.boardingSource) {
                        BoardingPointSource.VERIFIED -> " [확정 위치]"
                        BoardingPointSource.ESTIMATED -> " [경로 추정]"
                        BoardingPointSource.UNKNOWN -> " [위치 미확인]"
                    }

                    ElevatedButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            selectedKakaoTransit = choice
                            boardingStop = null
                            selectedPlace = null
                            candidateBoardingPlaces = emptyList()

                            mapCenter = choice.boardingPoint ?: selectedKakaoStartPlace?.point

                            status = "선택: ${choice.boardingStopName} (${choice.serviceName}) 승차 $sourceBadge"

                            requestWalk(WalkTarget.KAKAO_TRANSIT)
                        }
                    ) {
                        Text(
                            """
                            경로 ${index + 1}
                            총 소요시간: $timeFormatted | 환승: ${choice.transfers}회
                            첫 승차: ${choice.boardingStopName}$sourceBadge
                            노선: $typeLabel${choice.serviceName.ifBlank { "노선 정보 없음" }}
                            """.trimIndent()
                        )
                    }
                }

                // 추정/미확인 위치일 경우, 사용자가 선택 후보를 검색하여 확정할 수 있는 UI (규칙 3, 4, 5)
                selectedKakaoTransit?.let { choice ->
                    if (choice.boardingSource != BoardingPointSource.VERIFIED) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = "⚠️ '${choice.boardingStopName}' 승차 장소/출입구 확인 필요",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                Text(
                                    text = "현재 경로는 추정 좌표를 기준으로 계산되었습니다. 정확한 정류장/출입구를 검색해 직접 선택하세요.",
                                    style = MaterialTheme.typography.bodySmall
                                )

                                Button(
                                    enabled = !loading,
                                    onClick = {
                                        scope.launch {
                                            loading = true
                                            status = "'${choice.boardingStopName}' 상세 장소 검색 중..."
                                            try {
                                                candidateBoardingPlaces = kakaoRepository.searchPlaces(choice.boardingStopName)
                                                status = "검색된 후보 목록에서 정확한 승차 장소를 직접 선택하세요."
                                            } catch (e: Exception) {
                                                candidateBoardingPlaces = emptyList()
                                                status = "후보 검색 실패: ${e.message}"
                                            } finally {
                                                loading = false
                                            }
                                        }
                                    }
                                ) {
                                    Text("승차 장소/출입구 후보 검색")
                                }

                                // 규칙 5: 첫 번째 결과를 자동 선택하지 않고 사용자가 명시적으로 선택
                                candidateBoardingPlaces.forEach { place ->
                                    OutlinedButton(
                                        modifier = Modifier.fillMaxWidth(),
                                        onClick = {
                                            val updatedChoice = choice.copy(
                                                boardingPoint = place.point,
                                                boardingSource = BoardingPointSource.VERIFIED
                                            )
                                            selectedKakaoTransit = updatedChoice
                                            candidateBoardingPlaces = emptyList()
                                            mapCenter = place.point
                                            status = "승차 위치 확정: ${place.name}"
                                            requestWalk(WalkTarget.KAKAO_TRANSIT)
                                        }
                                    ) {
                                        Text("📍 ${place.name}\n${place.address}")
                                    }
                                }
                            }
                        }
                    }
                }

            } else {

                Text(
                    "기능 1. 출발역 → 도착역 (ODsay API)",
                    style = MaterialTheme.typography.titleLarge
                )

                // 출발역 검색
                OutlinedTextField(
                    value = startQuery,

                    onValueChange = {
                        startQuery = it
                    },

                    label = {
                        Text("출발역")
                    },

                    placeholder = {
                        Text("예: 서울역")
                    },

                    modifier = Modifier.fillMaxWidth()
                )

                Button(
                    enabled = !loading && startQuery.length >= 2,

                    onClick = {

                        scope.launch {

                            loading = true

                            try {

                                status = "출발역 검색 중..."

                                startResults =
                                    repository.searchStops(startQuery)

                                status = "출발역을 선택하세요."

                            } catch (e: Exception) {

                                startResults = emptyList()

                                status =
                                    "검색 오류: ${e.message}"

                            } finally {

                                loading = false
                            }
                        }
                    }
                ) {
                    Text("출발역 검색")
                }

                StopOptions(startResults) { stop ->

                    selectedStart = stop

                    startResults = emptyList()
                    transitPlans = emptyList()

                    boardingStop = null
                    walkRoute = null
                    kakaoWalkRoute = null

                    status = "출발역: ${stop.name}"
                }

                Text(
                    "선택된 출발역: ${selectedStart?.name ?: "없음"}"
                )

                // 도착역 검색
                OutlinedTextField(
                    value = endQuery,

                    onValueChange = {
                        endQuery = it
                    },

                    label = {
                        Text("도착역")
                    },

                    placeholder = {
                        Text("예: 동탄역")
                    },

                    modifier = Modifier.fillMaxWidth()
                )

                Button(
                    enabled = !loading && endQuery.length >= 2,

                    onClick = {

                        scope.launch {

                            loading = true

                            try {

                                status = "도착역 검색 중..."

                                endResults =
                                    repository.searchStops(endQuery)

                                status = "도착역을 선택하세요."

                            } catch (e: Exception) {

                                endResults = emptyList()

                                status =
                                    "검색 오류: ${e.message}"

                            } finally {

                                loading = false
                            }
                        }
                    }
                ) {
                    Text("도착역 검색")
                }

                StopOptions(endResults) { stop ->

                    selectedEnd = stop

                    endResults = emptyList()
                    transitPlans = emptyList()

                    boardingStop = null
                    walkRoute = null
                    kakaoWalkRoute = null

                    status = "도착역: ${stop.name}"
                }

                Text(
                    "선택된 도착역: ${selectedEnd?.name ?: "없음"}"
                )

                // 대중교통 경로 검색
                Button(
                    enabled = !loading &&
                            selectedStart != null &&
                            selectedEnd != null,

                    onClick = {

                        val start = selectedStart
                        val end = selectedEnd

                        if (start != null && end != null) {

                            scope.launch {

                                loading = true

                                try {

                                    status = "대중교통 경로 검색 중..."

                                    transitPlans =
                                        repository.searchTransitRoutes(
                                            start,
                                            end
                                        )

                                    status =
                                        if (transitPlans.isEmpty()) {
                                            "검색된 대중교통 경로가 없습니다."
                                        } else {
                                            "경로를 선택하세요."
                                        }

                                } catch (e: Exception) {

                                    transitPlans = emptyList()

                                    status =
                                        "경로 검색 오류: ${e.message}"

                                } finally {

                                    loading = false
                                }
                            }
                        }
                    }
                ) {
                    Text("대중교통 경로 찾기")
                }

                // 검색된 대중교통 경로 목록
                transitPlans.take(5).forEachIndexed { index, plan ->

                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth(),

                        onClick = {

                            selectBoardingStop(
                                plan.boardingStop
                            )
                        }
                    ) {

                        Text(
                            """
                            경로 ${index + 1}
                            총 소요시간: ${plan.totalMinutes}분
                            첫 탑승: ${plan.boardingStop.name}
                            노선: ${plan.serviceName}
                            방면: ${plan.direction}
                            """.trimIndent()
                        )
                    }
                }
            }
        }

        HorizontalDivider()

        // -----------------------------
        // 승차 지점 도보시간
        // -----------------------------

        Text(
            "선택된 승차 지점",
            style = MaterialTheme.typography.titleMedium
        )

        val activeBoardingName = when {
            selectedKakaoTransit != null ->
                "${selectedKakaoTransit?.boardingStopName} (${selectedKakaoTransit?.serviceName})"

            boardingStop != null ->
                boardingStop?.name.orEmpty()

            selectedPlace != null ->
                selectedPlace?.name.orEmpty()

            else ->
                "아직 선택하지 않았습니다."
        }

        Text(
            activeBoardingName
        )

        if (kakaoBoardingNotice.isNotBlank()) {
            Text(
                text = kakaoBoardingNotice,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }

        Button(
            enabled = (selectedKakaoTransit != null || boardingStop != null || selectedPlace != null) && !loading,

            onClick = {
                val target = when {
                    selectedKakaoTransit != null -> WalkTarget.KAKAO_TRANSIT
                    boardingStop != null -> WalkTarget.TRANSIT_STOP
                    else -> WalkTarget.KAKAO_PLACE
                }
                requestWalk(target)
            }
        ) {
            Text("현재 위치에서 승차 지점까지 도보 계산")
        }

        // 현재 위치
        currentPoint?.let { point ->

            Text(
                "현재 위치: ${point.y}, ${point.x}",
                style = MaterialTheme.typography.bodySmall
            )
        }

        // 도보 경로 결과
        walkRoute?.let { route ->

            Text(
                "도보 거리: ${route.distanceMeters}m"
            )

            Text(
                text = "도보 소요시간: %.1f분".format(
                    route.durationSeconds / 60.0
                ),

                style = MaterialTheme.typography.titleMedium
            )
        }

        HorizontalDivider()

        // -----------------------------
        // 긴급도 판단
        // -----------------------------

        Text(
            "탑승 긴급도 판단",
            style = MaterialTheme.typography.titleLarge
        )

        OutlinedTextField(
            value = arrivalMinutes,

            onValueChange = {
                arrivalMinutes = it
            },

            label = {
                Text("차량 도착까지 남은 시간(분)")
            },

            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = bufferMinutes,

            onValueChange = {
                bufferMinutes = it
            },

            label = {
                Text("승차 준비 여유시간(분)")
            },

            modifier = Modifier.fillMaxWidth()
        )

        Button(
            enabled = walkRoute != null && !loading,

            onClick = {

                val arrival =
                    arrivalMinutes.toDoubleOrNull()

                val buffer =
                    bufferMinutes.toDoubleOrNull()

                val route = walkRoute

                if (
                    arrival == null ||
                    buffer == null ||
                    arrival < 0 ||
                    buffer < 0 ||
                    route == null
                ) {

                    urgencyResult =
                        "시간을 올바르게 입력하세요."

                } else {

                    // 초 → 분 변환
                    val walkMin =
                        route.durationSeconds / 60.0

                    // 남는 시간 계산
                    val slack =
                        arrival - walkMin - buffer

                    // 긴급도 결정
                    val urgency = when {

                        slack >= 3 ->
                            Urgency.NONE

                        slack >= 1 ->
                            Urgency.LOW

                        slack >= 0 ->
                            Urgency.MEDIUM

                        slack >= -2 ->
                            Urgency.HIGH

                        else ->
                            Urgency.MISSED
                    }

                    // 안내 메시지
                    val message = when (urgency) {

                        Urgency.NONE ->
                            "여유 있음 · 진동 없음"

                        Urgency.LOW ->
                            "보통 걸음 · 진동 1회"

                        Urgency.MEDIUM ->
                            "빠른 걸음 권장 · 진동 2회"

                        Urgency.HIGH ->
                            "매우 촉박함 · 진동 3회"

                        Urgency.MISSED ->
                            "탑승 어려움 · 다음 편 확인"
                    }

                    urgencyResult =
                        "$message\n승차 준비까지 남는 시간: %.1f분"
                            .format(slack)

                    // 진동 실행
                    playVibration(
                        context,
                        urgency
                    )
                }
            }
        ) {
            Text("긴급도 계산 및 진동")
        }

        Text(
            urgencyResult,
            style = MaterialTheme.typography.titleMedium
        )

        HorizontalDivider()

        // -----------------------------
        // 상태 메시지
        // -----------------------------

        if (loading) {
            CircularProgressIndicator()
        }

        Text(
            status,
            style = MaterialTheme.typography.bodyMedium
        )

        Text(
            "※ 차량 도착시간은 현재 수동 입력입니다. " +
                    "도보 경로는 카카오 API를 이용하며, " +
                    "역 내부 이동 및 정확한 승강장 위치는 " +
                    "추가 검증이 필요합니다.",

            style = MaterialTheme.typography.bodySmall
        )
    }
}

// -----------------------------
// 역·정류장 선택 목록
// -----------------------------

@Composable
fun StopOptions(
    stops: List<TransitStop>,
    onChoose: (TransitStop) -> Unit
) {

    stops.take(8).forEach { stop ->

        TextButton(
            modifier = Modifier.fillMaxWidth(),

            onClick = {
                onChoose(stop)
            }
        ) {

            Text(
                "${stop.name} / " +
                        "${stop.line.ifBlank { "노선 정보 없음" }} " +
                        "(ID: ${stop.id})"
            )
        }
    }
}

// -----------------------------
// 긴급도별 진동 횟수
// -----------------------------

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