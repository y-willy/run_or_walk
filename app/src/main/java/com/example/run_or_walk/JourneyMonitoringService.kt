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
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Only an explicit, foreground user action starts this bounded location session. */
class JourneyMonitoringService : Service() {
    private val displayClock=GuidanceDisplayClock(System.currentTimeMillis(),android.os.SystemClock.elapsedRealtime(),android.os.SystemClock::elapsedRealtime)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var monitoringJob: Job? = null
    private var timeoutJob: Job? = null
    private var locationJob: Job? = null
    private var walkingJob: Job? = null
    private var routeTracker: WalkingRouteTracker? = null
    private var lastWalkingRequestAt = 0L
    private var generation = 0L
    private var transitions = MovementAdviceTransitions()
    private var livePaceCalibrator = WalkingPaceCalibrator()
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
                val updated = monitoringStateWithSettings(old, request, displayClock.now(), hasLocationPermission(this))
                publishMovement(updated)
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
        stopLocationTracking()
        monitoringJob?.cancel()
        timeoutJob?.cancel()
        transitions=MovementAdviceTransitions()
        livePaceCalibrator=WalkingPaceCalibrator()
        while (refreshRequests.tryReceive().isSuccess) { /* a new journey drops old refresh intents */ }
        currentRequest = request
        finishMessage = "이동 안내를 종료했어요."
        val prior=JourneyMonitoringController.state.value
        val handover=if(prior.request?.isSameJourney(request)==true)
            monitoringStateWithSettings(prior.copy(running=true),request,displayClock.now(),hasLocationPermission(this))
            else monitoringSelectionHandover(prior,request,displayClock.now(),hasLocationPermission(this))
        JourneyMonitoringController.publish(handover ?: JourneyMonitoringState(running = true, request = request,
            message = "화면이 꺼져도 이동 안내를 확인해요. 최대 60분 후 종료돼요."))
        startLocationTracking(request)
        monitoringJob = scope.launch { monitor() }
        timeoutJob = scope.launch {
            delay(MAX_SESSION_MILLIS)
            finishSession("60분 이동 안내가 끝났어요. 계속 필요하면 앱에서 다시 시작해 주세요.")
        }
        return START_NOT_STICKY
    }

    private suspend fun monitor() {
        val startedAt = android.os.SystemClock.elapsedRealtime()
        val sessionGeneration=generation
        var retryAt: Long? = null
        val alertGate = JourneyMonitoringAlertGate()
        var lastArrivals = JourneyMonitoringController.state.value.arrivals
        var lastVehicleId = currentRequest?.subwayChoice?.trainId
        var lastSubwayObservations = JourneyMonitoringController.state.value.subwayObservations
        while (sessionGeneration == generation && scope.isActive && android.os.SystemClock.elapsedRealtime() - startedAt < MAX_SESSION_MILLIS) {
            val cycleStartedAt = android.os.SystemClock.elapsedRealtime()
            val request = currentRequest ?: break
            if (!hasLocationPermission(this) || !JourneyMonitoringController.notificationsAllowed(this)) {
                finishSession("위치·알림 권한이 변경되어 이동 안내를 종료했어요. 권한을 확인하고 다시 시작해 주세요.")
                return
            }
            val now = displayClock.now()
            val deferredPositionRefresh = !request.isBus && retryAt?.let { it > now } == true
            if (retryAt?.let { it > now } == true && !deferredPositionRefresh) {
                val old=JourneyMonitoringController.state.value
                publishMovement(monitoringStateWithSettings(old.copy(arrivalRequestFailed=true,retryAtEpochMillis=retryAt),
                    request,now,hasLocationPermission(this)))
                delay(minOf(POLL_INTERVAL_MILLIS, (requireNotNull(retryAt) - now).coerceAtLeast(1_000L)))
                continue
            }
            var arrivals: RealtimeArrivalSnapshot? = null
            var vehicleId: String? = null
            var location: GuidanceLocation? = null
            var walking: GuidanceWalkingRoute? = null
            var measuredPace: Double? = null
            var failure = false
            var furtherServiceEnded = false
            var message: String? = null
            var subwayObservations = lastSubwayObservations
            var timetableAnalysis: SubwayTimetableAnalysis? = null
            var seoulRows=JourneyMonitoringController.state.value.seoulBusObservations
            var gyeonggiRows=JourneyMonitoringController.state.value.gyeonggiBusObservations
            var responseAt=JourneyMonitoringController.state.value.arrivalResponseAtEpochMillis
            try {
                if (deferredPositionRefresh) {
                    failure = true
                    message = "도착정보를 확인하고 있어요."
                    val days = timetableCalendar.serviceDays(now)
                    if (days.isNotEmpty()) timetableAnalysis =
                        analyzeSubwayWithScheduleFallback(timetableAnalyzer, requireNotNull(request.subwayStation),
                            request.subwayRoute, lastSubwayObservations, now, days, request.identity)
                } else if (request.isGyeonggi) {
                    val match = requireNotNull(request.gyeonggiMatch)
                    val incoming=gyeonggi.arrivals(match.stationId)
                    gyeonggiRows=mergeGyeonggiBusArrivals(match,gyeonggiRows.orEmpty(),incoming,displayClock.now())
                    responseAt=incoming.maxOfOrNull { it.fetchedAtMillis } ?: displayClock.now()
                    val selected = chooseMatchingGyeonggiArrival(match,requireNotNull(gyeonggiRows),displayClock.now())
                    arrivals = selected?.snapshot
                    vehicleId = selected?.vehicleId
                    if (selected == null) message = "선택한 경기버스 방향의 유효한 도착정보를 기다리고 있어요."
                } else if (request.isBus) {
                    val incoming=bus.arrivals(requireNotNull(request.busStop), requireNotNull(request.busRoute))
                    seoulRows=mergeSeoulBusArrivals(request.identity,seoulRows.orEmpty(),incoming,displayClock.now())
                    responseAt=incoming.mapNotNull { it.gatewayFetchedAtEpochMillis ?: it.snapshot.receivedAtEpochMillis }.maxOrNull() ?: displayClock.now()
                    val selected = chooseMatchingBusArrival(request.identity,requireNotNull(seoulRows),displayClock.now())
                    arrivals = selected?.snapshot
                    vehicleId = selected?.vehicleId
                    if (selected == null) message = "선택한 버스의 유효한 도착정보를 기다리고 있어요."
                    furtherServiceEnded = selected?.isLast == true && selected.snapshot.nextArrivalAtEpochMillis == null
                } else {
                    subwayObservations = subway.arrivals(requireNotNull(request.subwayStation))
                    lastSubwayObservations = subwayObservations
                    responseAt=subwayObservations.mapNotNull { it.gatewayFetchedAtEpochMillis ?: it.snapshot.receivedAtEpochMillis }.maxOrNull() ?: displayClock.now()
                    val days = timetableCalendar.serviceDays(displayClock.now())
                    if (days.isNotEmpty()) {
                        timetableAnalysis = analyzeSubwayWithScheduleFallback(timetableAnalyzer,
                            requireNotNull(request.subwayStation), request.subwayRoute,
                            subwayObservations, displayClock.now(), days, request.identity)
                    }
                    arrivalHistory.record(requireNotNull(request.subwayStation), subwayObservations,
                        timetableAnalysis?.estimates.orEmpty(), displayClock.now())
                    val selected = selectDestinationSubwayArrival(timetableAnalysis, displayClock.now(),request.subwayChoice) ?:
                        chooseMatchingSubwayArrival(request.identity, subwayObservations.filter { request.subwayChoice?.matches(it) != false }, displayClock.now()) ?:
                        if(selectTimetableEvidence(timetableAnalysis,displayClock.now(),request.subwayChoice) == null)
                            retainedSubwayArrivalDuringRefresh(JourneyMonitoringController.state.value.timetableAnalysis,
                                subwayObservations,request.identity,request.subwayChoice,displayClock.now()) else null
                    arrivals = selected?.snapshot
                    vehicleId = selected?.trainId
                    if (selected == null) message = "선택한 방향의 유효한 열차 정보를 기다리고 있어요."
                }
                if (!failure) retryAt = null
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                failure = true
                arrivals = lastArrivals?.takeIf { it.hasUsableOfficialEta(displayClock.now()) }
                vehicleId = lastVehicleId.takeIf { arrivals != null }
                retryAt = (error as? SeoulBusApiException)?.retryAfterEpochMillis
                    ?: (error as? SeoulSubwayApiException)?.retryAfterEpochMillis
                    ?: (error as? GyeonggiApiException)?.retryAfterEpochMillis
                message = (error as? SeoulBusApiException)?.message
                    ?: (error as? GyeonggiApiException)?.message
                    ?: (error as? SeoulSubwayApiException)?.message ?: "실시간 도착정보 연결을 다시 확인하고 있어요."
                if (!request.isBus) {
                    val days = timetableCalendar.serviceDays(displayClock.now())
                    if (days.isNotEmpty()) {
                        try {
                            timetableAnalysis = analyzeSubwayWithScheduleFallback(timetableAnalyzer,
                                requireNotNull(request.subwayStation), request.subwayRoute,
                                lastSubwayObservations, displayClock.now(), days, request.identity)
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { /* Keep the original API failure visible. */ }
                    }
                }
            }
            val live = JourneyMonitoringController.state.value
            location=live.location
            walking=live.walkingRoute
            measuredPace=live.calibratedSpeedMetersPerSecond
            if(sessionGeneration != generation) return
            val active = currentRequest ?: break
            if (!active.isSameJourney(request)) continue
            if (!hasLocationPermission(this) || !JourneyMonitoringController.notificationsAllowed(this)) {
                finishSession("위치·알림 권한이 변경되어 이동 안내를 종료했어요. 권한을 확인하고 다시 시작해 주세요.")
                return
            }
            val checkedAt = displayClock.now()
            // A first vehicle may pass while a request is in flight. Re-select surviving
            // rows before publishing, including failures and backoff; do not blank the next bus.
            if(request.isGyeonggi && gyeonggiRows!=null) {
                val selected=chooseMatchingGyeonggiArrival(requireNotNull(request.gyeonggiMatch),requireNotNull(gyeonggiRows),checkedAt)
                arrivals=selected?.snapshot;vehicleId=selected?.vehicleId
            } else if(request.isBus && seoulRows!=null) {
                val selected=chooseMatchingBusArrival(request.identity,requireNotNull(seoulRows),checkedAt)
                arrivals=selected?.snapshot;vehicleId=selected?.vehicleId
            }
            if (!failure) { lastArrivals = arrivals; lastVehicleId = vehicleId }
            val evidence = selectTimetableEvidence(timetableAnalysis, checkedAt,request.subwayChoice)
            val currentIdentity = arrivals?.identity ?: evidence?.identity ?: request.identity
            walking = walking?.copy(boardingIdentity = currentIdentity)
            val assessment = calculateMovementGuidance(AutoGuidanceInput(location,
                GuidanceBoarding(currentIdentity, request.boardingPoint, true), walking, arrivals,
                hasLocationPermission(this), failure, active.settings.bufferSeconds, evidence, furtherServiceEnded,
                JourneyMonitoringController.state.value.assessment), checkedAt,
                active.settings)
            val operation = subwayOperationGuidance(request.subwayStation, null, request.identity,
                subwayObservations, checkedAt, failure)
            val summary = if (assessment.unknownReason == AutoGuidanceUnknownReason.WALKING_OUTSIDE_RANGE)
                WALKING_OUTSIDE_RANGE_MESSAGE else if (assessment.status == AutoGuidanceStatus.UNKNOWN)
                evidence?.let { timetableArrivalText(it, checkedAt) } ?: operation?.let { movementGuidanceTitle(assessment,checkedAt) } ?: message ?: "최신 위치·도보·도착정보를 확인하고 있어요."
                else (evidence?.takeIf { assessment.arrivalSource != GuidanceArrivalSource.OFFICIAL_REALTIME }
                    ?.let { timetableArrivalText(it,checkedAt) + " · " } ?: "") +
                    if (assessment.status == AutoGuidanceStatus.NEXT_VEHICLE)
                        "다음 차량을 기다리세요. " + nextVehicleGuidanceText(assessment, checkedAt)
                    else movementGuidanceTitle(assessment,checkedAt)
            publishMovement(JourneyMonitoringState(true, active, assessment,
                location, walking, arrivals, summary, checkedAt, retryAt,
                live.walkingDistanceMeters.takeIf { walking != null },
                live.providerWalkingSeconds.takeIf { walking != null },
                live.walkingPoints.takeIf { walking != null }.orEmpty(), subwayObservations, timetableAnalysis,
                measuredPace, failure,seoulRows,gyeonggiRows,responseAt,arrivalError=message.takeIf { failure }))

            val displayedAssessment=JourneyMonitoringController.state.value.assessment ?: assessment
            if (displayedAssessment.status == AutoGuidanceStatus.UNKNOWN) notifications.cancel(ARRIVAL_ID)
            val event = alertGate.claim(currentIdentity, vehicleId?.takeIf(String::isNotBlank) ?: arrivals?.vehicleIdentityFallback,
                displayedAssessment, arrivals, checkedAt)
            if (event != null) {
                val text = if (event.kind == JourneyMonitoringAlertKind.ARRIVING)
                    "확인된 ${if (request.isBus) "버스" else "열차"}가 1분 이내 도착 예정이에요. 신호와 승차 위치를 확인해 주세요."
                    else (evidence?.takeIf { assessment.arrivalSource != GuidanceArrivalSource.OFFICIAL_REALTIME }
                        ?.let { timetableArrivalText(it,checkedAt) + " · " } ?: "") +
                        movementGuidanceTitle(displayedAssessment,checkedAt) +
                        if (event.status == AutoGuidanceStatus.NEXT_VEHICLE) " " + nextVehicleGuidanceText(assessment,checkedAt) else ""
                val visibleFor = arrivals?.let { monitoringAlertLifetimeMillis(event, it, checkedAt) }
                    ?: evidence?.let { (it.first.validUntilEpochMillis-checkedAt).coerceIn(0L,60_000L) } ?: 0L
                if (visibleFor > 0L) notifySafely(ARRIVAL_ID, arrivalNotification(active, text, visibleFor, checkedAt))
            }
            val remaining = (POLL_INTERVAL_MILLIS - (android.os.SystemClock.elapsedRealtime() - cycleStartedAt)).coerceAtLeast(1_000L)
            // Coalesce taps made during a request; repository caches and real Retry-After stay authoritative.
            withTimeoutOrNull(remaining) { refreshRequests.receive() }
        }
        finishSession("60분 이동 안내가 끝났어요. 계속 필요하면 앱에서 다시 시작해 주세요.")
    }

    /** Location proceeds independently of network latency and the provider ETA polling interval. */
    private fun startLocationTracking(request: JourneyMonitoringRequest) {
        val token=generation
        locationJob=scope.launch {
            val health=launch {
                while(isActive) {
                    delay(4_000L)
                    if(token != generation) break
                    val active=currentRequest ?: break
                    if(!hasLocationPermission(this@JourneyMonitoringService) || !JourneyMonitoringController.notificationsAllowed(this@JourneyMonitoringService)) {
                        finishSession("위치·알림 권한이 변경되어 이동 안내를 종료했어요."); break
                    }
                    val latest=JourneyMonitoringController.state.value
                    val now=displayClock.now()
                    if(latest.lastUpdatedEpochMillis?.let { now-it in 0..3_500L } == true) continue
                    publishMovement(monitoringStateWithSettings(latest,active,now,true))
                }
            }
            try {
                liveLocationSamples(this@JourneyMonitoringService).collect { fix ->
                    if(token != generation || currentRequest?.isSameJourney(request) != true) return@collect
                    updateWalkingLocation(fix)
                    val now=displayClock.now()
                    if(walkingJob?.isActive != true && shouldRefreshWalkingRoute(routeTracker,fix,now,lastWalkingRequestAt)) {
                        lastWalkingRequestAt=now
                        walkingJob=scope.launch {
                            try {
                                val route=kakao.searchWalk(fix.point,request.boardingPoint)
                                if(!validWalkingAccess(route,fix.point,request.boardingPoint)) throw KakaoApiException(KakaoFailure.INVALID_RESPONSE)
                                if(token != generation || currentRequest?.isSameJourney(request) != true) return@launch
                                val latest=JourneyMonitoringController.state.value.location
                                if(latest!=null && pedestrianDistance(latest.point,fix.point)>35.0) return@launch
                                routeTracker=WalkingRouteTracker(fix.point,route,displayClock.now())
                                JourneyMonitoringController.state.value.location?.let { updateWalkingLocation(it) }
                            } catch(cancelled: CancellationException) { throw cancelled }
                            catch(_: Exception) { /* Keep the previous real route only until its original expiry. */ }
                        }
                    }
                }
            } catch(cancelled: CancellationException) { throw cancelled }
            catch(error: Exception) {
                if(token == generation) {
                    val old=JourneyMonitoringController.state.value
                    val revoked=error is SecurityException || error is DeviceLocationException && error.failure in
                        setOf(LocationFailure.PERMISSION_REQUIRED,LocationFailure.SERVICES_DISABLED)
                    publishMovement(monitoringStateWithSettings(if(revoked) old.copy(location=null,walkingRoute=null) else old,
                        currentRequest ?: request,displayClock.now(),hasLocationPermission(this@JourneyMonitoringService)))
                }
            } finally { health.cancel() }
        }
    }

    private fun updateWalkingLocation(fix: GuidanceLocation) {
        val request=currentRequest ?: return
        val latest=JourneyMonitoringController.state.value.location
        if(latest != null && fix.observedAtEpochMillis < latest.observedAtEpochMillis) return
        if(!hasLocationPermission(this) || !JourneyMonitoringController.notificationsAllowed(this)) {
            finishSession("위치·알림 권한이 변경되어 이동 안내를 종료했어요."); return
        }
        val now=displayClock.now()
        val old=JourneyMonitoringController.state.value
        if(old.request?.isSameJourney(request) != true) return
        val pace=livePaceCalibrator.observe(fix,now)
        val result=updateLiveWalkingEvidence(LiveWalkingEvidence(old.location,old.walkingRoute,
            old.walkingDistanceMeters,old.providerWalkingSeconds,old.walkingPoints),routeTracker,fix,
            request.identity,request.boardingPoint,request.settings,pace,now)
        val next=old.copy(location=result.location,walkingRoute=result.walking,walkingDistanceMeters=result.distanceMeters,
            providerWalkingSeconds=result.providerSeconds,walkingPoints=result.points,
            calibratedSpeedMetersPerSecond=pace,lastUpdatedEpochMillis=now)
        publishMovement(monitoringStateWithSettings(next,request,now,true))
    }

    private fun publishMovement(state: JourneyMonitoringState) {
        val request=currentRequest ?: return
        if(!state.running || !JourneyMonitoringController.state.value.running || state.request?.isSameJourney(request) != true) return
        val assessment=state.assessment ?: return
        val now=displayClock.now()
        val change=transitions.observe(assessment,now,request.vibrationEnabled)
        val message=movementGuidanceTitle(change.assessment,now)
        JourneyMonitoringController.publish(state.copy(assessment=change.assessment,message=message))
        notifySafely(ONGOING_ID,ongoingNotification(request,message,state.arrivals,now))
        if(change.vibrationCount > 0) playMovementVibration(this,change.vibrationCount)
    }

    private fun stopLocationTracking() {
        generation++
        locationJob?.cancel(); locationJob=null
        walkingJob?.cancel(); walkingJob=null
        routeTracker=null; lastWalkingRequestAt=0L
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
            .setWhen(checkedAt ?: displayClock.now()).setShowWhen(true)
            .setContentIntent(contentIntent()).setOngoing(true).setOnlyAlertOnce(true).setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .addAction(0, "안내 종료", stopIntent()).build()
    }

    private fun arrivalNotification(request: JourneyMonitoringRequest, message: String, visibleForMillis: Long,
        checkedAt: Long): Notification =
        NotificationCompat.Builder(this, SILENT_ARRIVAL_CHANNEL)
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
        stopLocationTracking()
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
        stopLocationTracking()
        monitoringJob?.cancel()
        timeoutJob?.cancel()
        scope.cancel()
        monitoringJob=null; timeoutJob=null; currentRequest=null
        refreshRequests.close()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        notifications.cancel(ARRIVAL_ID)
        stopForeground(STOP_FOREGROUND_REMOVE)
        JourneyMonitoringController.publish(JourneyMonitoringState(message = finishMessage))
        super.onDestroy()
    }


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
    AutoGuidanceStatus.WALK -> "걸으세요"
    AutoGuidanceStatus.FAST_WALK -> "뛰세요"
    AutoGuidanceStatus.NEXT_VEHICLE -> "다음 차량을 기다리세요."
    AutoGuidanceStatus.UNKNOWN -> "최신 정보를 확인하고 있어요."
}
private fun clockTime(epochMillis: Long): String = DateTimeFormatter.ofPattern("HH:mm:ss")
    .format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))
