package com.example.run_or_walk

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class JourneyMonitoringState(
    val running: Boolean = false,
    val request: JourneyMonitoringRequest? = null,
    val assessment: AutoGuidanceAssessment? = null,
    val location: GuidanceLocation? = null,
    val walkingRoute: GuidanceWalkingRoute? = null,
    val arrivals: RealtimeArrivalSnapshot? = null,
    val message: String = "",
    val lastUpdatedEpochMillis: Long? = null,
    val retryAtEpochMillis: Long? = null,
    val walkingDistanceMeters: Int? = null,
    val providerWalkingSeconds: Int? = null,
    val walkingPoints: List<MapPoint> = emptyList(),
    val subwayObservations: List<OfficialSubwayArrival> = emptyList(),
    val timetableAnalysis: SubwayTimetableAnalysis? = null
) {
    val isRunning: Boolean get() = running
}

/** Process-local observation only. A saved route never silently restarts location monitoring. */
internal object JourneyMonitoringController {
    private val mutableState = MutableStateFlow(JourneyMonitoringState())
    val state: StateFlow<JourneyMonitoringState> = mutableState.asStateFlow()

    fun start(context: Context, request: JourneyMonitoringRequest): Boolean {
        val message = when {
            !request.isValid() -> "공식 승차 장소와 노선·방향을 먼저 선택해 주세요."
            !hasLocationPermission(context) -> "화면 꺼짐 안내에는 위치 권한이 필요해요."
            !notificationsAllowed(context) -> "화면 꺼짐 도착 알림을 받으려면 알림 권한을 허용해 주세요."
            else -> null
        }
        if (message != null) { publishFailure(message); return false }
        return try {
            ContextCompat.startForegroundService(context, Intent(context, JourneyMonitoringService::class.java)
                .setAction(JourneyMonitoringService.ACTION_START)
                .putExtra(JourneyMonitoringService.EXTRA_REQUEST, JourneyMonitoringRequestCodec.encode(request)))
            true
        } catch (_: Exception) {
            publishFailure("이동 안내를 시작하지 못했어요. 앱 화면을 연 상태에서 다시 시작해 주세요.")
            false
        }
    }

    fun stop(context: Context) {
        // stopService does not create a new service after a process interruption.
        context.stopService(Intent(context, JourneyMonitoringService::class.java))
        mutableState.value = JourneyMonitoringState(message = "이동 안내를 종료했어요.")
    }

    fun updateSettings(context: Context, settings: JourneySettings, vibrationEnabled: Boolean) {
        val current = mutableState.value
        if (!current.running) return
        val request = current.request?.copy(settings = settings, vibrationEnabled = vibrationEnabled) ?: return
        if (request == current.request) return
        runCatching {
            context.startService(Intent(context, JourneyMonitoringService::class.java)
                .setAction(JourneyMonitoringService.ACTION_UPDATE)
                .putExtra(JourneyMonitoringService.EXTRA_REQUEST, JourneyMonitoringRequestCodec.encode(request)))
        }
    }

    fun refresh(context: Context) {
        if (!mutableState.value.running) return
        runCatching {
            context.startService(Intent(context, JourneyMonitoringService::class.java)
                .setAction(JourneyMonitoringService.ACTION_REFRESH))
        }
    }

    internal fun publish(state: JourneyMonitoringState) { mutableState.value = state }
    internal fun publishFailure(message: String) { mutableState.value = JourneyMonitoringState(message = message) }

    fun notificationsAllowed(context: Context): Boolean =
        (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED) && NotificationManagerCompat.from(context).areNotificationsEnabled()
}

/** Reuse actual walking distance while preserving every observation's original freshness. */
internal fun monitoringStateWithSettings(
    state: JourneyMonitoringState,
    request: JourneyMonitoringRequest,
    nowEpochMillis: Long,
    locationPermissionGranted: Boolean
): JourneyMonitoringState {
    if (state.request?.isSameJourney(request) != true) return state
    val walking = state.walkingRoute?.let { previous ->
        val distance = state.walkingDistanceMeters
        val providerSeconds = state.providerWalkingSeconds
        if (distance == null || providerSeconds == null || distance < 0 || providerSeconds < 0) null
        else previous.copy(durationSeconds = request.settings.walkingSeconds(distance, providerSeconds))
    }
    val evidence = selectTimetableEvidence(state.timetableAnalysis, nowEpochMillis)
    val identity = state.arrivals?.identity ?: evidence?.identity ?: request.identity
    val assessment = calculateAutoGuidance(AutoGuidanceInput(state.location,
        GuidanceBoarding(identity, request.boardingPoint, true), walking?.copy(boardingIdentity = identity), state.arrivals,
        locationPermissionGranted, bufferSeconds = request.settings.bufferSeconds, timetableArrivals = evidence,
        furtherServiceEnded = state.assessment?.nextVehicleState == NextVehicleState.SERVICE_ENDED,
        previousAssessment = state.assessment), nowEpochMillis,
        AutoGuidancePolicy(fastSpeedKmh = request.settings.fastSpeedKmh))
    val message = if (assessment.status == AutoGuidanceStatus.UNKNOWN) {
        val operation = subwayOperationGuidance(request.subwayStation, null, request.identity,
            state.subwayObservations, nowEpochMillis)
        if (operation != null) operation.message
        else if (state.assessment?.status == AutoGuidanceStatus.UNKNOWN) state.message
        else "최신 위치·도보·도착정보가 확인될 때까지 판단을 보류해요."
    } else if (assessment.status == AutoGuidanceStatus.NEXT_VEHICLE)
        "다음 차량을 기다리세요. " + nextVehicleGuidanceText(assessment, nowEpochMillis)
    else monitoringAssessmentMessage(assessment.status, request.isBus)
    return state.copy(request = request, walkingRoute = walking, assessment = assessment, message = message)
}
