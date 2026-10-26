package com.example.run_or_walk

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Only an explicit, foreground user action starts this bounded location session. */
class JourneyMonitoringService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var monitoringJob: Job? = null
    private var timeoutJob: Job? = null
    private var currentRequest: JourneyMonitoringRequest? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val refreshRequests = Channel<Unit>(Channel.CONFLATED)
    private var finishMessage = "이동 안내를 종료했어요."
    private val notifications by lazy { getSystemService(NotificationManager::class.java) }
    private val subway by lazy { SeoulSubwayRepository(this) }
    private val timetableRepository by lazy { OfficialTimetableRepository(this) }
    private val arrivalHistory by lazy { SubwayArrivalHistory(this) }
    private val timetableAnalyzer by lazy { SubwayTimetableAnalyzer(timetableRepository) }
    private val timetableCalendar by lazy { OfficialServiceCalendar(this) }
    private val bus by lazy { SeoulBusRepository() }
    private val gyeonggi by lazy { GyeonggiBusRepository() }
    private val kakao by lazy { KakaoApiRepository(BuildConfig.KAKAO_REST_KEY) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { finishSession("이동 안내를 종료했어요."); return START_NOT_STICKY }
        if (intent?.action == ACTION_REFRESH) {
            if (currentRequest != null) refreshRequests.trySend(Unit) else stopSelf(startId)
            return START_NOT_STICKY
        }
        val request = JourneyMonitoringRequestCodec.decode(intent?.getStringExtra(EXTRA_REQUEST))
        if (intent?.action == ACTION_UPDATE) {
            if (!hasLocationPermission(this) || !JourneyMonitoringController.notificationsAllowed(this)) {
                finishSession("위치·알림 권한이 변경되어 이동 안내를 종료했어요.")
                return START_NOT_STICKY
            }
            if (request != null && currentRequest?.isSameJourney(request) == true) {
                currentRequest = request
                val old = JourneyMonitoringController.state.value
                val updated = monitoringStateWithSettings(old, request, System.currentTimeMillis(), hasLocationPermission(this))
                JourneyMonitoringController.publish(updated)
                notifySafely(ONGOING_ID, ongoingNotification(request, updated.message, updated.arrivals,
                    updated.lastUpdatedEpochMillis))
            } else if (currentRequest == null) stopSelf(startId)
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_START || request == null) {
            if (currentRequest == null) finishSession("공식 승차 장소·노선 선택을 확인하고 이동 안내를 다시 시작해 주세요.")
            return START_NOT_STICKY
        }
        if (!hasLocationPermission(this) || !JourneyMonitoringController.notificationsAllowed(this)) {
            finishSession("위치·알림 권한을 허용하고 앱 화면에서 이동 안내를 다시 시작해 주세요.")
            return START_NOT_STICKY
        }
        if (currentRequest?.isSameJourney(request) == true && monitoringJob?.isActive == true) {
            currentRequest = request
            JourneyMonitoringController.publish(JourneyMonitoringController.state.value.copy(request = request))
            return START_NOT_STICKY
        }
        try {
            createChannels()
            val initial = ongoingNotification(request, "현재 위치와 실시간 도착정보를 확인하고 있어요.")
            if (Build.VERSION.SDK_INT >= 29) startForeground(ONGOING_ID, initial, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            else startForeground(ONGOING_ID, initial)
            acquireSessionWakeLock()
        } catch (_: Exception) {
            finishSession("화면 꺼짐 안내를 시작하지 못했어요. 위치·알림 권한과 기기의 위치 설정을 확인해 주세요.")
            return START_NOT_STICKY
        }
        monitoringJob?.cancel()
        timeoutJob?.cancel()
        while (refreshRequests.tryReceive().isSuccess) { /* a new journey drops old refresh intents */ }
        currentRequest = request
        finishMessage = "이동 안내를 종료했어요."
        JourneyMonitoringController.publish(JourneyMonitoringState(running = true, request = request,
            message = "화면이 꺼져도 이동 안내를 확인해요. 최대 60분 후 종료돼요."))
        monitoringJob = scope.launch { monitor() }
        timeoutJob = scope.launch {
            delay(MAX_SESSION_MILLIS)
            finishSession("60분 이동 안내가 끝났어요. 계속 필요하면 앱에서 다시 시작해 주세요.")
        }
        return START_NOT_STICKY
    }

    private suspend fun monitor() {
        val startedAt = android.os.SystemClock.elapsedRealtime()
        var cachedWalk: MonitoringWalk? = null
        var retryAt: Long? = null
        val alertGate = JourneyMonitoringAlertGate()
        var lastArrivals: RealtimeArrivalSnapshot? = null
        var lastVehicleId: String? = null
        while (scope.isActive && android.os.SystemClock.elapsedRealtime() - startedAt < MAX_SESSION_MILLIS) {
            val cycleStartedAt = android.os.SystemClock.elapsedRealtime()
            val request = currentRequest ?: break
            if (!hasLocationPermission(this) || !JourneyMonitoringController.notificationsAllowed(this)) {
                finishSession("위치·알림 권한이 변경되어 이동 안내를 종료했어요. 권한을 확인하고 다시 시작해 주세요.")
                return
            }
            val now = System.currentTimeMillis()
            if (retryAt?.let { it > now } == true) {
                val previous = JourneyMonitoringController.state.value
                if (previous.assessment?.status == AutoGuidanceStatus.NEXT_VEHICLE &&
                    previous.arrivals?.hasUsableOfficialEta(now) == true) {
                    val assessment = previous.assessment.copy(nextVehicleState = NextVehicleState.API_ERROR,
                        recommendedArrivalAtEpochMillis = null)
                    val detail = "다음 차량을 기다리세요. " + nextVehicleGuidanceText(assessment, now)
                    JourneyMonitoringController.publish(previous.copy(assessment = assessment, message = detail, retryAtEpochMillis = retryAt))
                    notifySafely(ONGOING_ID, ongoingNotification(request, detail, previous.arrivals, now))
                } else publishUnknown(request, "도착정보 재조회 대기 중이에요. 서버 안내 시각 이후 다시 확인해요.", retryAt)
                delay(minOf(POLL_INTERVAL_MILLIS, (requireNotNull(retryAt) - now).coerceAtLeast(1_000L)))
                continue
            }
            var arrivals: RealtimeArrivalSnapshot? = null
            var vehicleId: String? = null
            var location: GuidanceLocation? = null
            var walking: GuidanceWalkingRoute? = null
            var failure = false
            var furtherServiceEnded = false
            var message: String? = null
            var subwayObservations = emptyList<OfficialSubwayArrival>()
            var timetableAnalysis: SubwayTimetableAnalysis? = null
            try {
                if (request.isGyeonggi) {
                    val match = requireNotNull(request.gyeonggiMatch)
                    val selected = chooseMatchingGyeonggiArrival(match,
                        gyeonggi.arrivals(match.stationId), System.currentTimeMillis())
                    arrivals = selected?.snapshot
                    vehicleId = selected?.vehicleId
                    if (selected == null) message = "선택한 경기버스 방향의 유효한 도착정보를 기다리고 있어요."
                } else if (request.isBus) {
                    val selected = chooseMatchingBusArrival(request.identity,
                        bus.arrivals(requireNotNull(request.busStop), requireNotNull(request.busRoute)), System.currentTimeMillis())
                    arrivals = selected?.snapshot
                    vehicleId = selected?.vehicleId
                    if (selected == null) message = "선택한 버스의 유효한 도착정보를 기다리고 있어요."
                    furtherServiceEnded = selected?.isLast == true && selected.snapshot.nextArrivalAtEpochMillis == null
                } else {
                    subwayObservations = subway.arrivals(requireNotNull(request.subwayStation))
                    val days = timetableCalendar.serviceDays(System.currentTimeMillis())
                    val day = days.firstOrNull()
                    if (day != null && request.subwayRoute != null) {
                        timetableAnalysis = timetableAnalyzer.analyze(requireNotNull(request.subwayStation), request.subwayRoute,
                            subwayObservations, System.currentTimeMillis(), day.serviceDate, day.dayType, day.sourceVersion,
                            additionalServiceDays = days.drop(1))
                    }
                    arrivalHistory.record(requireNotNull(request.subwayStation), subwayObservations,
                        timetableAnalysis?.estimates.orEmpty(), System.currentTimeMillis())
                    val selected = selectDestinationSubwayArrival(timetableAnalysis, System.currentTimeMillis()) ?:
                        chooseMatchingSubwayArrival(request.identity, subwayObservations, System.currentTimeMillis())
                    arrivals = selected?.snapshot
                    vehicleId = selected?.trainId
                    if (selected == null) message = "선택한 방향의 유효한 열차 정보를 기다리고 있어요."
                }
                retryAt = null
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                failure = true
                arrivals = lastArrivals?.takeIf { it.hasUsableOfficialEta(System.currentTimeMillis()) }
                vehicleId = lastVehicleId.takeIf { arrivals != null }
                retryAt = (error as? SeoulBusApiException)?.retryAfterEpochMillis
                    ?: (error as? SeoulSubwayApiException)?.retryAfterEpochMillis
                    ?: (error as? GyeonggiApiException)?.retryAfterEpochMillis
                message = (error as? SeoulBusApiException)?.message
                    ?: (error as? GyeonggiApiException)?.message
                    ?: (error as? SeoulSubwayApiException)?.message ?: "실시간 도착정보 연결을 다시 확인하고 있어요."
                if (!request.isBus && request.subwayRoute != null) {
                    val days = timetableCalendar.serviceDays(System.currentTimeMillis())
                    val day = days.firstOrNull()
                    if (day != null) {
                        try {
                            timetableAnalysis = timetableAnalyzer.analyze(requireNotNull(request.subwayStation),
                                request.subwayRoute, emptyList(), System.currentTimeMillis(), day.serviceDate,
                                day.dayType, day.sourceVersion, additionalServiceDays = days.drop(1))
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { /* Keep the original API failure visible. */ }
                    }
                }
            }
            try {
                location = getCurrentLocationSample(this)
                val old = cachedWalk
                if (old == null || System.currentTimeMillis() - old.fetchedAt !in 0L..90_000L ||
                    monitoringDistance(old.origin, location.point) > 25.0) {
                    cachedWalk = null
                    val route = kakao.searchWalk(location.point, request.boardingPoint)
                    if (!validWalkingAccess(route, location.point, request.boardingPoint)) {
                        throw KakaoApiException(KakaoFailure.INVALID_RESPONSE)
                    }
                    cachedWalk = MonitoringWalk(location.point, route, System.currentTimeMillis())
                }
                val activeSettings = currentRequest?.settings ?: request.settings
                walking = cachedWalk?.let {
                    GuidanceWalkingRoute(it.origin, request.boardingPoint, request.identity,
                        activeSettings.walkingSeconds(it.route.distanceMeters, it.route.durationSeconds),
                        it.fetchedAt, 120_000L, it.route.distanceMeters)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                walking = null
                message = message ?: friendlyLocationFailure(error) ?: friendlyKakaoFailure(error)
                    ?: "현재 위치·도보 경로를 다시 확인하고 있어요."
            }
            val active = currentRequest ?: break
            if (!active.isSameJourney(request)) continue
            if (!hasLocationPermission(this) || !JourneyMonitoringController.notificationsAllowed(this)) {
                finishSession("위치·알림 권한이 변경되어 이동 안내를 종료했어요. 권한을 확인하고 다시 시작해 주세요.")
                return
            }
            val checkedAt = System.currentTimeMillis()
            if (!failure) { lastArrivals = arrivals; lastVehicleId = vehicleId }
            val evidence = selectTimetableEvidence(timetableAnalysis, checkedAt)
            val currentIdentity = arrivals?.identity ?: evidence?.identity ?: request.identity
            walking = walking?.copy(boardingIdentity = currentIdentity)
            val assessment = calculateAutoGuidance(AutoGuidanceInput(location,
                GuidanceBoarding(currentIdentity, request.boardingPoint, true), walking, arrivals,
                hasLocationPermission(this), failure, active.settings.bufferSeconds, evidence, furtherServiceEnded,
                JourneyMonitoringController.state.value.assessment), checkedAt,
                AutoGuidancePolicy(fastSpeedKmh = active.settings.fastSpeedKmh))
            val operation = subwayOperationGuidance(request.subwayStation, null, request.identity,
                subwayObservations, checkedAt, failure)
            val summary = if (assessment.status == AutoGuidanceStatus.UNKNOWN)
                evidence?.let { timetableArrivalText(it, checkedAt) } ?: operation?.message ?: message ?: "최신 위치·도보·도착정보를 확인하고 있어요."
                else (if (assessment.arrivalSource != GuidanceArrivalSource.OFFICIAL_REALTIME) "시간표 추정 · " else "") +
                    if (assessment.status == AutoGuidanceStatus.NEXT_VEHICLE)
                        "다음 차량을 기다리세요. " + nextVehicleGuidanceText(assessment, checkedAt)
                    else monitoringAssessmentMessage(assessment.status, request.isBus)
            JourneyMonitoringController.publish(JourneyMonitoringState(true, active, assessment,
                location, walking, arrivals, summary, checkedAt, retryAt,
                cachedWalk?.route?.distanceMeters.takeIf { walking != null },
                cachedWalk?.route?.durationSeconds.takeIf { walking != null },
                cachedWalk?.route?.points.orEmpty().takeIf { walking != null }.orEmpty(), subwayObservations, timetableAnalysis))
            notifySafely(ONGOING_ID, ongoingNotification(active, summary, arrivals, checkedAt))
            if (assessment.status == AutoGuidanceStatus.UNKNOWN) notifications.cancel(ARRIVAL_ID)
            val event = alertGate.claim(currentIdentity, vehicleId?.takeIf(String::isNotBlank) ?: arrivals?.vehicleIdentityFallback,
                assessment, arrivals, checkedAt)
            if (event != null) {
                val text = if (event.kind == JourneyMonitoringAlertKind.ARRIVING)
                    "확인된 ${if (request.isBus) "버스" else "열차"}가 1분 이내 도착 예정이에요. 신호와 승차 위치를 확인해 주세요."
                    else monitoringAssessmentMessage(event.status, request.isBus)
                val visibleFor = arrivals?.let { monitoringAlertLifetimeMillis(event, it, checkedAt) } ?: 0L
                if (visibleFor > 0L) notifySafely(ARRIVAL_ID, arrivalNotification(active, text, visibleFor, checkedAt))
            }
            val remaining = (POLL_INTERVAL_MILLIS - (android.os.SystemClock.elapsedRealtime() - cycleStartedAt)).coerceAtLeast(1_000L)
            // Coalesce taps made during a request; repository caches and real Retry-After stay authoritative.
            withTimeoutOrNull(remaining) { refreshRequests.receive() }
        }
        finishSession("60분 이동 안내가 끝났어요. 계속 필요하면 앱에서 다시 시작해 주세요.")
    }

    private fun publishUnknown(request: JourneyMonitoringRequest, message: String, retryAt: Long?) {
        val now = System.currentTimeMillis()
        JourneyMonitoringController.publish(JourneyMonitoringState(true, request,
            AutoGuidanceAssessment(AutoGuidanceStatus.UNKNOWN, bufferSeconds = request.settings.bufferSeconds),
            message = message, lastUpdatedEpochMillis = now, retryAtEpochMillis = retryAt))
        notifySafely(ONGOING_ID, ongoingNotification(request, message, checkedAt = now))
        notifications.cancel(ARRIVAL_ID)
    }

    private fun createChannels() {
        notifications.createNotificationChannel(NotificationChannel(ONGOING_CHANNEL, "이동 안내 실행 상태",
            NotificationManager.IMPORTANCE_LOW).apply { description = "사용자가 시작한 위치·도착정보 안내와 종료 버튼"; setShowBadge(false) })
        notifications.createNotificationChannel(NotificationChannel(ARRIVAL_CHANNEL, "차량 도착·이동 안내",
            NotificationManager.IMPORTANCE_HIGH).apply { description = "확인된 차량의 도착 임박과 이동 판단 변경"; enableVibration(true) })
        notifications.createNotificationChannel(NotificationChannel(SILENT_ARRIVAL_CHANNEL, "차량 도착·이동 안내 (진동 없음)",
            NotificationManager.IMPORTANCE_HIGH).apply { enableVibration(false); setSound(null, null) })
    }

    private fun contentIntent(): PendingIntent = PendingIntent.getActivity(this, 0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun stopIntent(): PendingIntent = PendingIntent.getService(this, 1,
        Intent(this, JourneyMonitoringService::class.java).setAction(ACTION_STOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun ongoingNotification(request: JourneyMonitoringRequest, message: String,
        arrivals: RealtimeArrivalSnapshot? = null, checkedAt: Long? = null): Notification {
        val details = listOfNotNull(message,
            arrivals?.firstArrivalAtEpochMillis?.let { "차량 예정 ${clockTime(it)}" },
            checkedAt?.let { "최근 확인 ${clockTime(it)}" }, "최대 60분 · 알림에서 종료 가능").joinToString("\n")
        return NotificationCompat.Builder(this, ONGOING_CHANNEL)
            .setSmallIcon(R.drawable.ic_journey_notification).setContentTitle("${request.boardingName} 이동 안내")
            .setContentText(message).setStyle(NotificationCompat.BigTextStyle().bigText(details))
            .setWhen(checkedAt ?: System.currentTimeMillis()).setShowWhen(true)
            .setContentIntent(contentIntent()).setOngoing(true).setOnlyAlertOnce(true).setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .addAction(0, "안내 종료", stopIntent()).build()
    }

    private fun arrivalNotification(request: JourneyMonitoringRequest, message: String, visibleForMillis: Long,
        checkedAt: Long): Notification =
        NotificationCompat.Builder(this, if (request.vibrationEnabled) ARRIVAL_CHANNEL else SILENT_ARRIVAL_CHANNEL)
            .setSmallIcon(R.drawable.ic_journey_notification).setContentTitle(request.serviceLabel.ifBlank { request.boardingName })
            .setContentText(message).setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setWhen(checkedAt).setShowWhen(true)
            .setContentIntent(contentIntent()).setAutoCancel(true).setOnlyAlertOnce(false)
            .setTimeoutAfter(visibleForMillis)
            .setCategory(NotificationCompat.CATEGORY_EVENT).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build()

    private fun notifySafely(id: Int, notification: Notification) {
        if (!JourneyMonitoringController.notificationsAllowed(this)) return
        try { notifications.notify(id, notification) } catch (_: SecurityException) { /* permission was revoked */ }
    }

    private fun acquireSessionWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        val manager = getSystemService(PowerManager::class.java)
        wakeLock = manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PaceNudge:JourneyMonitoring").apply {
            setReferenceCounted(false)
            acquire(MAX_SESSION_MILLIS + 5_000L)
        }
    }

    private fun finishSession(message: String) {
        finishMessage = message
        monitoringJob?.cancel()
        timeoutJob?.cancel()
        monitoringJob = null
        timeoutJob = null
        currentRequest = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        JourneyMonitoringController.publish(JourneyMonitoringState(message = message))
        notifications.cancel(ARRIVAL_ID)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        monitoringJob?.cancel()
        timeoutJob?.cancel()
        scope.cancel()
        refreshRequests.close()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        notifications.cancel(ARRIVAL_ID)
        stopForeground(STOP_FOREGROUND_REMOVE)
        JourneyMonitoringController.publish(JourneyMonitoringState(message = finishMessage))
        super.onDestroy()
    }

    private data class MonitoringWalk(val origin: MapPoint, val route: KakaoWalkingRoute, val fetchedAt: Long)

    companion object {
        internal const val ACTION_START = "com.example.run_or_walk.MONITOR_START"
        internal const val ACTION_UPDATE = "com.example.run_or_walk.MONITOR_UPDATE"
        internal const val ACTION_STOP = "com.example.run_or_walk.MONITOR_STOP"
        internal const val ACTION_REFRESH = "com.example.run_or_walk.MONITOR_REFRESH"
        internal const val EXTRA_REQUEST = "journey_request"
        private const val ONGOING_CHANNEL = "journey_monitoring"
        private const val ARRIVAL_CHANNEL = "journey_arrival"
        private const val SILENT_ARRIVAL_CHANNEL = "journey_arrival_silent"
        private const val ONGOING_ID = 4100
        private const val ARRIVAL_ID = 4101
        private const val POLL_INTERVAL_MILLIS = 30_000L
        private const val MAX_SESSION_MILLIS = 60 * 60_000L
    }
}

internal fun monitoringAssessmentMessage(status: AutoGuidanceStatus, isBus: Boolean): String = when (status) {
    AutoGuidanceStatus.WALK -> "걸어가세요. 안전 여유시간을 포함해 확인했어요."
    AutoGuidanceStatus.FAST_WALK -> "빠른 걸음으로 이동하세요. 신호와 안전한 보행을 우선하세요."
    AutoGuidanceStatus.NEXT_VEHICLE -> "다음 차량을 기다리세요."
    AutoGuidanceStatus.UNKNOWN -> "최신 정보를 확인하고 있어요."
}
private fun clockTime(epochMillis: Long): String = DateTimeFormatter.ofPattern("HH:mm:ss")
    .format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))
private fun monitoringDistance(first: MapPoint, second: MapPoint): Double {
    val latitude = Math.toRadians(second.y - first.y)
    val longitude = Math.toRadians(second.x - first.x)
    val a = sin(latitude / 2).let { it * it } + cos(Math.toRadians(first.y)) * cos(Math.toRadians(second.y)) *
        sin(longitude / 2).let { it * it }
    return 6_371_000.0 * 2 * asin(sqrt(a.coerceIn(0.0, 1.0)))
}
