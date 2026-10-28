package com.example.run_or_walk

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
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
    val timetableAnalysis: SubwayTimetableAnalysis? = null,
    val calibratedSpeedMetersPerSecond: Double? = null,
    val arrivalRequestFailed: Boolean = false,
    val seoulBusObservations: List<OfficialSeoulBusArrival>? = null,
    val gyeonggiBusObservations: List<GyeonggiBusArrivalDisplay>? = null,
    val arrivalResponseAtEpochMillis: Long? = null,
    val arrivalError: String? = null,
    val walkingCalculatedAtEpochMillis:Long? = null,
    val walkingGeometry:WalkingRouteTracker?=null,
    val walkingRequestElapsed:Long?=null
) {
    val isRunning: Boolean get() = running
}

/** Process-local observation only. A saved route never silently restarts location monitoring. */
internal object JourneyMonitoringController {
    private val mutableState = MutableStateFlow(JourneyMonitoringState())
    val state: StateFlow<JourneyMonitoringState> = mutableState.asStateFlow()

    var lastStartError: String? = null; private set
    private var pendingHandover: JourneyMonitoringState? = null
    private var startSequence=0L
    private data class StartTicket(val token:Long,val request:JourneyMonitoringRequest,val result:CompletableDeferred<Boolean>,val canActivate:()->Boolean)
    private var startTicket:StartTicket?=null
    internal fun isStartCurrent(request:JourneyMonitoringRequest,token:Long):Boolean =
        if(token==0L) startTicket?.result?.isCompleted!=false else startTicket?.let {
            if(it.token!=token || it.result.isCompleted || !it.request.isSameJourney(request)) false
            else if(!it.canActivate()) {
                lastStartError="선택 경로가 변경되어 새 안내 전환을 취소했어요. 기존 안내는 유지됩니다."
                it.result.complete(false);false
            } else true
        }==true
    internal fun completeStart(request:JourneyMonitoringRequest,token:Long,error:String?=null) {
        if(!isStartCurrent(request,token)) return
        if(error!=null) lastStartError=error
        startTicket?.result?.complete(error==null)
    }
    private fun cancelPendingStart() {
        startTicket?.result?.complete(false);pendingHandover=null
    }
    /** Queuing a service Intent is not a successful journey switch. */
    suspend fun startAndAwait(context:Context,request:JourneyMonitoringRequest,handover:JourneyMonitoringState?=null,canActivate:()->Boolean={true}):Boolean {
        if(!start(context,request,handover,canActivate)) return false
        val ticket=startTicket ?: return false
        try {
            return withTimeoutOrNull(50_000L) {ticket.result.await()} ?: run {
                if(startTicket===ticket) {lastStartError="새 안내 준비 시간이 초과됐어요. 기존 안내는 유지됩니다.";cancelPendingStart()}
                false
            }
        } catch(cancelled:CancellationException) {
            if(startTicket===ticket) cancelPendingStart()
            throw cancelled
        }
    }
    internal fun consumeHandover(request:JourneyMonitoringRequest):JourneyMonitoringState? =
        pendingHandover?.takeIf { it.request?.isSameJourney(request)==true }.also { if(it!=null) pendingHandover=null }

    fun start(context: Context, request: JourneyMonitoringRequest, handover: JourneyMonitoringState? = null,canActivate:()->Boolean={true}): Boolean {
        val previous=mutableState.value
        lastStartError=null
        val message = when {
            !request.isValid() -> "공식 승차 장소와 노선·방향을 먼저 선택해 주세요."
            !hasLocationPermission(context) -> "화면 꺼짐 안내에는 위치 권한이 필요해요."
            !notificationsAllowed(context) -> "화면 꺼짐 도착 알림을 받으려면 알림 권한을 허용해 주세요."
            else -> null
        }
        if (message != null) {
            lastStartError=message
            if(!previous.running) publishFailure(message)
            return false
        }
        return try {
            cancelPendingStart()
            val ticket=StartTicket(++startSequence,request,CompletableDeferred(),canActivate)
            startTicket=ticket
            pendingHandover=handover?.takeIf { it.request?.isSameJourney(request)==true }
            ContextCompat.startForegroundService(context, Intent(context, JourneyMonitoringService::class.java)
                .setAction(JourneyMonitoringService.ACTION_START)
                .putExtra(JourneyMonitoringService.EXTRA_START_TOKEN,ticket.token)
                .putExtra(JourneyMonitoringService.EXTRA_REQUEST, JourneyMonitoringRequestCodec.encode(request)))
            true
        } catch (_: Exception) {
            cancelPendingStart()
            lastStartError="이동 안내를 시작하지 못했어요. 앱 화면을 연 상태에서 다시 시작해 주세요."
            if(previous.running) publish(previous) else publishFailure(requireNotNull(lastStartError))
            false
        }
    }

    fun stop(context: Context) {
        // stopService does not create a new service after a process interruption.
        cancelPendingStart()
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

    internal fun publish(state: JourneyMonitoringState) {
        if(!state.running) {cancelPendingStart();startTicket=null}
        mutableState.value = state
    }
    internal fun publishFailure(message: String) { mutableState.value = JourneyMonitoringState(message = message) }

    fun notificationsAllowed(context: Context): Boolean =
        (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED) && NotificationManagerCompat.from(context).areNotificationsEnabled()
}

/** A tap on another train at the same platform reuses only the original, still-aged observations. */
internal fun monitoringSelectionHandover(state: JourneyMonitoringState, request: JourneyMonitoringRequest,
    now: Long, locationPermissionGranted: Boolean): JourneyMonitoringState? {
    val old = state.request ?: return null
    if (!state.running || old.isBus || request.isBus || old.boardingPoint != request.boardingPoint ||
        old.subwayStation != request.subwayStation || old.subwayRoute != request.subwayRoute ||
        old.identity.directionId != request.identity.directionId) return null
    val arrivals = selectDestinationSubwayArrival(state.timetableAnalysis,now,request.subwayChoice)
        ?: chooseMatchingSubwayArrival(request.identity,state.subwayObservations.filter {
            request.subwayChoice?.matches(it) != false },now)
    val seed = state.copy(request=request,arrivals=arrivals?.snapshot,assessment=null,
        walkingRoute=state.walkingRoute?.copy(boardingIdentity=request.identity))
    return monitoringStateWithSettings(seed,request,now,locationPermissionGranted)
}

/** Reuse actual walking distance while preserving every observation's original freshness. */
internal fun monitoringStateWithSettings(
    state: JourneyMonitoringState,
    request: JourneyMonitoringRequest,
    nowEpochMillis: Long,
    locationPermissionGranted: Boolean
): JourneyMonitoringState {
    if (state.request?.isSameJourney(request) != true) return state
    val currentArrival=when {
        request.isGyeonggi && state.gyeonggiBusObservations!=null ->
            chooseMatchingGyeonggiArrival(requireNotNull(request.gyeonggiMatch),state.gyeonggiBusObservations,nowEpochMillis)?.snapshot
        request.isBus && state.seoulBusObservations!=null ->
            chooseMatchingBusArrival(request.identity,state.seoulBusObservations,nowEpochMillis)?.snapshot
        else -> state.arrivals?.takeIf { (!request.isBus || it.identity==request.identity) && it.hasUsableOfficialEta(nowEpochMillis) }
    }
    val walking = state.walkingRoute?.let { previous ->
        val distance = state.walkingDistanceMeters
        val providerSeconds = state.providerWalkingSeconds
        if (distance == null || providerSeconds == null || distance < 0 || providerSeconds < 0) null
        else previous.copy(durationSeconds = request.settings.walkingSeconds(distance, providerSeconds,
            state.calibratedSpeedMetersPerSecond.takeIf { state.location?.let { nowEpochMillis-it.observedAtEpochMillis in 0L..30_000L } == true }))
    }
    val evidence = selectTimetableEvidence(state.timetableAnalysis, nowEpochMillis,request.subwayChoice)
    val identity = currentArrival?.identity ?: evidence?.identity ?: request.identity
    val assessment = calculateMovementGuidance(AutoGuidanceInput(state.location,
        GuidanceBoarding(identity, request.boardingPoint, true), walking?.copy(boardingIdentity = identity), currentArrival,
        locationPermissionGranted, arrivalRequestFailed = state.arrivalRequestFailed,
        bufferSeconds = request.settings.bufferSeconds, timetableArrivals = evidence,
        furtherServiceEnded = state.assessment?.nextVehicleState == NextVehicleState.SERVICE_ENDED,
        previousAssessment = state.assessment), nowEpochMillis,
        request.settings)
    val estimatePrefix = evidence?.takeIf { assessment.arrivalSource != GuidanceArrivalSource.OFFICIAL_REALTIME }
        ?.let { timetableArrivalText(it,nowEpochMillis) + " · " }.orEmpty()
    val message = if (assessment.unknownReason == AutoGuidanceUnknownReason.WALKING_OUTSIDE_RANGE)
        WALKING_OUTSIDE_RANGE_MESSAGE else if (assessment.status == AutoGuidanceStatus.UNKNOWN) {
        val operation = subwayOperationGuidance(request.subwayStation, null, request.identity,
            state.subwayObservations, nowEpochMillis)
        if (evidence != null) timetableArrivalText(evidence,nowEpochMillis)
        else if (operation != null) movementGuidanceTitle(assessment,nowEpochMillis)
        else if (state.assessment?.status == AutoGuidanceStatus.UNKNOWN) state.message
        else "최신 위치·도보·도착정보가 확인될 때까지 판단을 보류해요."
    } else if (assessment.status == AutoGuidanceStatus.NEXT_VEHICLE)
        estimatePrefix + movementGuidanceTitle(assessment,nowEpochMillis) + " · " + nextVehicleGuidanceText(assessment, nowEpochMillis)
    else estimatePrefix + movementGuidanceTitle(assessment,nowEpochMillis)
    return state.copy(request = request, walkingRoute = walking, arrivals=currentArrival, assessment = assessment, message = message)
}

/** Process-local handover only: no GPS, route geometry or ETA is put in the service Intent. */
internal fun monitoringScreenHandover(screen: JourneyScreenState,request: JourneyMonitoringRequest,
    now: Long,permissionGranted: Boolean): JourneyMonitoringState? {
    if(!request.isValid() || !screen.boardingConfirmed || screen.boarding?.point!=request.boardingPoint ||
        screen.confirmedServiceIdentity!=request.identity) return null
    val rows=screen.busArrivalOptions.takeIf { request.isBus && !request.isGyeonggi }
    val ggRows=screen.gyeonggiArrivals.takeIf { request.isGyeonggi }
    val arrival=when {
        request.isGyeonggi -> chooseMatchingGyeonggiArrival(requireNotNull(request.gyeonggiMatch),ggRows.orEmpty(),now)?.snapshot
        request.isBus -> chooseMatchingBusArrival(request.identity,rows.orEmpty(),now)?.snapshot
        else -> screen.selectedArrival?.snapshot?.takeIf { it.hasUsableOfficialEta(now) }
    }
    val state=JourneyMonitoringState(running=true,request=request,location=screen.location,
        walkingRoute=screen.guidanceWalkingRoute,arrivals=arrival,
        walkingDistanceMeters=screen.walkRoute?.distanceMeters,providerWalkingSeconds=screen.walkRoute?.durationSeconds,
        walkingPoints=screen.walkingPoints,subwayObservations=screen.arrivalOptions,timetableAnalysis=screen.timetableAnalysis,
        arrivalRequestFailed=screen.arrivalRequestFailed,seoulBusObservations=rows,gyeonggiBusObservations=ggRows,
        arrivalResponseAtEpochMillis=screen.arrivalResponseAtEpochMillis,
        arrivalError=(screen.liveError ?: screen.gyeonggiMessage).takeIf { screen.arrivalRequestFailed },
        walkingCalculatedAtEpochMillis=screen.walkingCalculatedAtEpochMillis,
        calibratedSpeedMetersPerSecond=screen.walkingCalibrationMetersPerSecond,
        walkingGeometry=screen.walkingTracker,walkingRequestElapsed=screen.walkingRefreshCadence.lastRequestedElapsed)
    return monitoringStateWithSettings(state,request,now,permissionGranted)
}

/** Same source-aware fallback wording as the foreground; known statuses use the shared title. */
internal fun monitoringGuidanceHeadline(state:JourneyMonitoringState,now:Long):String {
    val assessment=state.assessment ?: return "정보 확인 중"
    if(assessment.status!=AutoGuidanceStatus.UNKNOWN) return movementGuidanceTitle(assessment,now)
    if(state.arrivals?.hasUsableOfficialEta(now)==true || selectTimetableEvidence(state.timetableAnalysis,now,state.request?.subwayChoice)!=null)
        return unknownGuidanceTitle(assessment.unknownReason,"이동 정보 확인 필요")
    val seoul=state.seoulBusObservations.orEmpty();val gg=state.gyeonggiBusObservations.orEmpty()
    val noService=when {
        state.request?.isGyeonggi==true -> gg.isEmpty() || gg.all {it.serviceEnded}
        state.request?.isBus==true -> seoul.isEmpty() || seoul.all {row -> row.issues.any {it in setOf("SERVICE_ENDED","BUS_SERVICE_ENDED")} || Regex("운행\\s*종료").containsMatchIn(row.officialArrivalMessage)}
        else -> state.subwayObservations.isEmpty()
    }
    return unknownGuidanceTitle(assessment.unknownReason,missingArrivalText(state.arrivalResponseAtEpochMillis,seoul.isNotEmpty() || gg.isNotEmpty() || state.subwayObservations.isNotEmpty(),state.arrivalRequestFailed,noService,now))
}
