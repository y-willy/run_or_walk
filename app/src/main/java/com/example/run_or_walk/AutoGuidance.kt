package com.example.run_or_walk

import kotlin.math.asin
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

enum class AutoGuidanceStatus { WALK, FAST_WALK, NEXT_VEHICLE, UNKNOWN }

/** Source-data trust, distinct from transport success and boarding identity matching. */
enum class ArrivalTrustState { VALID, STALE, FUTURE_TIMESTAMP, INVALID_ETA, UNKNOWN }
enum class ArrivalTimePrecision { SECONDS, MINUTES }
enum class GuidanceArrivalSource { OFFICIAL_REALTIME, OFFICIAL_TIMETABLE_POSITION, OFFICIAL_TIMETABLE_SCHEDULE }
enum class NextVehicleState { ETA_PENDING, ETA_AVAILABLE, LATER_VEHICLES_REQUIRED, API_ERROR, SERVICE_ENDED }

enum class AutoGuidanceUnknownReason {
    LOCATION_PERMISSION_REQUIRED, LOCATION_UNAVAILABLE, LOCATION_INACCURATE, LOCATION_STALE,
    BOARDING_UNCONFIRMED,
    ARRIVAL_UNAVAILABLE, ARRIVAL_FAILED, ARRIVAL_MISMATCH, ARRIVAL_STALE, ARRIVAL_UNCERTAIN,
    ARRIVAL_FUTURE_TIMESTAMP, ARRIVAL_INVALID_ETA, NEXT_ARRIVAL_UNAVAILABLE, NO_CATCHABLE_TRAIN,
    WALKING_UNAVAILABLE, WALKING_OUTSIDE_RANGE, WALKING_STALE, WALKING_MISMATCH, INVALID_INPUT
}

/** Official provider IDs, never a station-name or coordinate-only match.
 * Seoul subway: authority + statnId + subwayId + updnLine + an exact service/destination variant.
 */
data class OfficialBoardingIdentity(
    val authority: String,
    val stopId: String,
    val routeId: String,
    val directionId: String,
    val serviceVariant: String
) {
    val isComplete: Boolean
        get() = listOf(authority, stopId, routeId, directionId, serviceVariant).all(String::isNotBlank)
}

data class GuidanceBoarding(
    val identity: OfficialBoardingIdentity,
    val point: MapPoint,
    val confirmed: Boolean
)

data class GuidanceLocation(
    val point: MapPoint,
    val accuracyMeters: Double,
    val observedAtEpochMillis: Long,
    /** Fused provider speed, kept separate from the reported position accuracy. */
    val speedMetersPerSecond: Double? = null,
    val speedAccuracyMetersPerSecond: Double? = null
)

/** A real walking response, tied to the coordinates and official boarding selection queried. */
data class GuidanceWalkingRoute(
    val origin: MapPoint,
    val destination: MapPoint,
    val boardingIdentity: OfficialBoardingIdentity,
    val durationSeconds: Int,
    val fetchedAtEpochMillis: Long,
    val validForMillis: Long,
    /** Distance from actual provider walking geometry. */
    val distanceMeters: Int? = null
)

/** Absolute arrival instants normalized by the official adapter; next arrival may be absent.
 * fetchedAt must reflect the source observation time, not receipt time for a cached response.
 * validForMillis covers every included arrival: cap expiry to the earliest source expiry
 * when the first and next vehicle rows have different observation times.
 * It comes from provider policy and is also capped by AutoGuidancePolicy.
 */
data class RealtimeArrivalSnapshot(
    val identity: OfficialBoardingIdentity,
    val firstArrivalAtEpochMillis: Long,
    val nextArrivalAtEpochMillis: Long? = null,
    val fetchedAtEpochMillis: Long,
    val validForMillis: Long,
    val reliable: Boolean = true,
    val trustState: ArrivalTrustState = ArrivalTrustState.VALID,
    val etaPrecision: ArrivalTimePrecision = ArrivalTimePrecision.SECONDS,
    val sourceTimestampAvailable: Boolean = true,
    val receivedAtEpochMillis: Long? = null,
    /** An API slot is not a fabricated vehicle ID; it only distinguishes official ETA entries. */
    val vehicleIdentityFallback: String? = null,
    val laterArrivalAtEpochMillis: List<Long> = emptyList(),
    val nextArrivalPrecision: ArrivalTimePrecision? = null
)

data class TimetableArrivalEvidence(
    val identity: OfficialBoardingIdentity,
    val first: OfficialTimetableEstimate,
    val next: OfficialTimetableEstimate? = null,
    val later: List<OfficialTimetableEstimate> = emptyList()
)

fun RealtimeArrivalSnapshot.hasUsableOfficialEta(now: Long): Boolean =
    reliable && trustState == ArrivalTrustState.VALID && validForMillis > 0 &&
        fetchedAtEpochMillis <= now && now - fetchedAtEpochMillis <= minOf(validForMillis, 600_000L) &&
        (receivedAtEpochMillis == null || receivedAtEpochMillis <= now + 60_000L && now - receivedAtEpochMillis <= 60_000L) &&
        firstArrivalAtEpochMillis > now

data class AutoGuidanceInput(
    val location: GuidanceLocation?,
    val boarding: GuidanceBoarding?,
    val walkingRoute: GuidanceWalkingRoute?,
    val arrivals: RealtimeArrivalSnapshot?,
    val locationPermissionGranted: Boolean,
    val arrivalRequestFailed: Boolean = false,
    val bufferSeconds: Int = 120,
    val timetableArrivals: TimetableArrivalEvidence? = null,
    val furtherServiceEnded: Boolean = false,
    val previousAssessment: AutoGuidanceAssessment? = null
)

/** Conservative defaults; the adapter must use a shorter validity when its provider requires it. */
data class AutoGuidancePolicy(
    val fastSpeedKmh: Double = 6.0,
    val maxLocationAccuracyMeters: Double = 50.0,
    val maxLocationAgeMillis: Long = 30_000L,
    val maxWalkingAgeMillis: Long = 120_000L,
    val maxArrivalAgeMillis: Long = 600_000L,
    val maxArrivalReceiptAgeMillis: Long = 60_000L,
    val maxWalkingOriginDistanceMeters: Double = 35.0,
    val maxWalkingDestinationDistanceMeters: Double = 15.0
)

data class AutoGuidanceAssessment(
    val status: AutoGuidanceStatus,
    val unknownReason: AutoGuidanceUnknownReason? = null,
    val firstArrivalAtEpochMillis: Long? = null,
    val nextArrivalAtEpochMillis: Long? = null,
    val walkingSeconds: Int? = null,
    /** Fast estimate using real route length and a declared brisk walking speed. */
    val fastTravelSeconds: Int? = null,
    val bufferSeconds: Int = 120,
    val arrivalFetchedAtEpochMillis: Long? = null,
    val fastSpeedKmh: Double = 6.0,
    val arrivalSource: GuidanceArrivalSource = GuidanceArrivalSource.OFFICIAL_REALTIME,
    val arrivalPrecision: ArrivalTimePrecision = ArrivalTimePrecision.SECONDS,
    val firstArrivalLatestAtEpochMillis: Long? = null,
    val estimateLabel: String? = null,
    val nextVehicleState: NextVehicleState? = null,
    val recommendedArrivalAtEpochMillis: Long? = null,
    val recommendedArrivalPrecision: ArrivalTimePrecision = arrivalPrecision,
    val boardingIdentity: OfficialBoardingIdentity? = null
)

/** Pure calculation with an injected wall clock. All timestamps are epoch milliseconds;
 * walking, fast-travel and buffer durations are seconds. Equality includes the safety buffer.
 * The estimate cannot account for crossings, signals, individual fitness or walking conditions.
 */
fun calculateAutoGuidance(
    input: AutoGuidanceInput,
    nowEpochMillis: Long,
    policy: AutoGuidancePolicy = AutoGuidancePolicy()
): AutoGuidanceAssessment {
    fun unknown(reason: AutoGuidanceUnknownReason): AutoGuidanceAssessment {
        val previous = input.previousAssessment
        val location = input.location
        val walking = input.walkingRoute
        val boarding = input.boarding
        if (reason in setOf(AutoGuidanceUnknownReason.ARRIVAL_UNAVAILABLE, AutoGuidanceUnknownReason.ARRIVAL_FAILED,
                AutoGuidanceUnknownReason.ARRIVAL_STALE) && previous?.status == AutoGuidanceStatus.NEXT_VEHICLE &&
            previous.boardingIdentity == boarding?.identity && boarding?.confirmed == true &&
            input.locationPermissionGranted && location != null && walking != null &&
            location.accuracyMeters in 0.0..policy.maxLocationAccuracyMeters &&
            isFresh(location.observedAtEpochMillis, nowEpochMillis, policy.maxLocationAgeMillis) &&
            isFresh(walking.fetchedAtEpochMillis, nowEpochMillis, minOf(walking.validForMillis,policy.maxWalkingAgeMillis)) &&
            walking.boardingIdentity == boarding.identity &&
            walking.distanceMeters != null && walking.distanceMeters >= 0 &&
            previous.firstArrivalAtEpochMillis?.let { it-nowEpochMillis <
                (minOf(walking.durationSeconds,ceil(walking.distanceMeters*3.6/policy.fastSpeedKmh).toInt()).toLong()+input.bufferSeconds)*1000L } == true &&
            distanceMeters(walking.origin,location.point) <= policy.maxWalkingOriginDistanceMeters &&
            distanceMeters(walking.destination,boarding.point) <= policy.maxWalkingDestinationDistanceMeters &&
            previous.firstArrivalAtEpochMillis?.let { nowEpochMillis-it in -3_600_000L..3_600_000L } == true) {
            return previous.copy(nextArrivalAtEpochMillis = null, recommendedArrivalAtEpochMillis = null,
                nextVehicleState = when {
                    input.furtherServiceEnded -> NextVehicleState.SERVICE_ENDED
                    input.arrivalRequestFailed -> NextVehicleState.API_ERROR
                    else -> NextVehicleState.ETA_PENDING
                })
        }
        return AutoGuidanceAssessment(
        status = AutoGuidanceStatus.UNKNOWN,
        unknownReason = reason,
        bufferSeconds = input.bufferSeconds,
        fastSpeedKmh = policy.fastSpeedKmh
        )
    }

    if (nowEpochMillis < 0L || input.bufferSeconds < 0 || !policy.isValid()) {
        return unknown(AutoGuidanceUnknownReason.INVALID_INPUT)
    }
    if (!input.locationPermissionGranted) {
        return unknown(AutoGuidanceUnknownReason.LOCATION_PERMISSION_REQUIRED)
    }
    val location = input.location
        ?: return unknown(AutoGuidanceUnknownReason.LOCATION_UNAVAILABLE)
    if (!location.point.isValidCoordinate() || !location.accuracyMeters.isFinite() ||
        location.accuracyMeters < 0.0 || location.accuracyMeters > policy.maxLocationAccuracyMeters
    ) {
        return unknown(AutoGuidanceUnknownReason.LOCATION_INACCURATE)
    }
    if (!isFresh(location.observedAtEpochMillis, nowEpochMillis, policy.maxLocationAgeMillis)) {
        return unknown(AutoGuidanceUnknownReason.LOCATION_STALE)
    }
    val boarding = input.boarding
    if (boarding == null || !boarding.confirmed || !boarding.identity.isComplete ||
        !boarding.point.isValidCoordinate()
    ) {
        return unknown(AutoGuidanceUnknownReason.BOARDING_UNCONFIRMED)
    }
    if (requiresTransitAccess(location.point, boarding.point))
        return unknown(AutoGuidanceUnknownReason.WALKING_OUTSIDE_RANGE)
    // A refresh error does not erase a still-valid received ETA or a proved missed-vehicle decision.
    if (input.arrivalRequestFailed && input.arrivals?.hasUsableOfficialEta(nowEpochMillis) != true && input.timetableArrivals == null)
        return unknown(AutoGuidanceUnknownReason.ARRIVAL_FAILED)
    val arrivals = input.arrivals
    val scheduled = input.timetableArrivals
    val useTimetable = scheduled != null && arrivals?.hasUsableOfficialEta(nowEpochMillis) != true
    val firstArrival: Long
    val nextArrival: Long?
    val fetchedAt: Long
    val precision: ArrivalTimePrecision
    val firstLatest: Long?
    val estimateLabel: String?
    if (useTimetable) {
        val evidence = requireNotNull(scheduled)
        if (evidence.identity != boarding.identity) return unknown(AutoGuidanceUnknownReason.ARRIVAL_MISMATCH)
        val first = evidence.first
        fun validEstimate(value: OfficialTimetableEstimate) =
            value.identity.stopId == boarding.identity.stopId && value.identity.routeId == boarding.identity.routeId &&
                value.identity.directionId == boarding.identity.directionId &&
                value.sourceTimestampEpochMillis <= value.receivedAtEpochMillis &&
                value.receivedAtEpochMillis <= nowEpochMillis &&
                nowEpochMillis <= value.validUntilEpochMillis &&
                value.earliestArrivalAtEpochMillis <= value.latestArrivalAtEpochMillis &&
                value.latestArrivalAtEpochMillis > nowEpochMillis
        if (!validEstimate(first)) return unknown(AutoGuidanceUnknownReason.ARRIVAL_STALE)
        val next = evidence.next?.takeIf { validEstimate(it) && it.trainNo != first.trainNo &&
            it.midpointArrivalAtEpochMillis() > first.midpointArrivalAtEpochMillis() }
        firstArrival = first.midpointArrivalAtEpochMillis()
        nextArrival = next?.midpointArrivalAtEpochMillis()
        fetchedAt = first.sourceTimestampEpochMillis
        precision = ArrivalTimePrecision.MINUTES
        firstLatest = first.latestArrivalAtEpochMillis
        estimateLabel = first.label
    } else {
        val actual = arrivals ?: return unknown(AutoGuidanceUnknownReason.ARRIVAL_UNAVAILABLE)
        if (actual.identity != boarding.identity) return unknown(AutoGuidanceUnknownReason.ARRIVAL_MISMATCH)
        when (actual.trustState) {
            ArrivalTrustState.STALE -> return unknown(AutoGuidanceUnknownReason.ARRIVAL_STALE)
            ArrivalTrustState.FUTURE_TIMESTAMP -> return unknown(AutoGuidanceUnknownReason.ARRIVAL_FUTURE_TIMESTAMP)
            ArrivalTrustState.INVALID_ETA -> return unknown(AutoGuidanceUnknownReason.ARRIVAL_INVALID_ETA)
            ArrivalTrustState.UNKNOWN -> return unknown(AutoGuidanceUnknownReason.ARRIVAL_UNCERTAIN)
            ArrivalTrustState.VALID -> Unit
        }
        if (!actual.reliable) return unknown(AutoGuidanceUnknownReason.ARRIVAL_UNCERTAIN)
        val received = actual.receivedAtEpochMillis
        if (actual.fetchedAtEpochMillis > nowEpochMillis ||
            received?.let { it > nowEpochMillis + 60_000L } == true) {
            return unknown(AutoGuidanceUnknownReason.ARRIVAL_FUTURE_TIMESTAMP)
        }
        if (actual.validForMillis <= 0L ||
            !isFresh(actual.fetchedAtEpochMillis, nowEpochMillis,
                minOf(actual.validForMillis, policy.maxArrivalAgeMillis)) ||
            (received != null && nowEpochMillis - received > policy.maxArrivalReceiptAgeMillis) ||
            actual.firstArrivalAtEpochMillis < nowEpochMillis) {
            return unknown(AutoGuidanceUnknownReason.ARRIVAL_STALE)
        }
        firstArrival = actual.firstArrivalAtEpochMillis
        nextArrival = actual.nextArrivalAtEpochMillis?.takeIf { it > firstArrival }
        fetchedAt = actual.fetchedAtEpochMillis
        precision = actual.etaPrecision
        firstLatest = null
        estimateLabel = null
    }
    val walking = input.walkingRoute
        ?: return unknown(AutoGuidanceUnknownReason.WALKING_UNAVAILABLE)
    if (walking.durationSeconds < 0) return unknown(AutoGuidanceUnknownReason.INVALID_INPUT)
    if (walking.validForMillis <= 0L ||
        !isFresh(walking.fetchedAtEpochMillis, nowEpochMillis,
            minOf(walking.validForMillis, policy.maxWalkingAgeMillis))
    ) {
        return unknown(AutoGuidanceUnknownReason.WALKING_STALE)
    }
    if (walking.boardingIdentity != boarding.identity ||
        !walking.origin.isValidCoordinate() || !walking.destination.isValidCoordinate() ||
        distanceMeters(walking.origin, location.point) > policy.maxWalkingOriginDistanceMeters ||
        distanceMeters(walking.destination, boarding.point) > policy.maxWalkingDestinationDistanceMeters
    ) {
        return unknown(AutoGuidanceUnknownReason.WALKING_MISMATCH)
    }

    val distance = walking.distanceMeters
        ?: return unknown(AutoGuidanceUnknownReason.WALKING_UNAVAILABLE)
    if (distance < 0) return unknown(AutoGuidanceUnknownReason.INVALID_INPUT)
    if ((distance == 0 && walking.durationSeconds > 0) ||
        (distance > 0 && walking.durationSeconds == 0)) {
        return unknown(AutoGuidanceUnknownReason.WALKING_UNAVAILABLE)
    }
    val estimate = ceil(distance.toDouble() * 3.6 / policy.fastSpeedKmh)
        .coerceAtMost(Int.MAX_VALUE.toDouble()).toInt()
    // Provider time may already reflect a faster pace.
    val fastSeconds = minOf(walking.durationSeconds, estimate)
    val remainingMillis = firstArrival - nowEpochMillis
    val walkingWithBufferMillis = (walking.durationSeconds.toLong() + input.bufferSeconds) * 1_000L
    val fastWithBufferMillis = (fastSeconds.toLong() + input.bufferSeconds) * 1_000L
    val rawStatus = when {
        remainingMillis >= walkingWithBufferMillis -> AutoGuidanceStatus.WALK
        remainingMillis >= fastWithBufferMillis -> AutoGuidanceStatus.FAST_WALK
        else -> AutoGuidanceStatus.NEXT_VEHICLE
    }
    // Escalate immediately when time is short. Relax FAST_WALK back to WALK only
    // after a small positive margin, so GPS velocity jitter cannot alternate advice.
    val previous = input.previousAssessment
    val status = if (rawStatus == AutoGuidanceStatus.WALK &&
        previous?.status == AutoGuidanceStatus.FAST_WALK &&
        previous.boardingIdentity == boarding.identity &&
        previous.firstArrivalAtEpochMillis?.let { kotlin.math.abs(it - firstArrival) <= 15_000L } == true &&
        remainingMillis - walkingWithBufferMillis < 20_000L
    ) AutoGuidanceStatus.FAST_WALK else rawStatus
    val recommended = (listOfNotNull(nextArrival) + if (useTimetable) scheduled?.later.orEmpty()
        .filter { it.validUntilEpochMillis >= nowEpochMillis && it.identity.directionId == boarding.identity.directionId }
        .map { it.midpointArrivalAtEpochMillis() }
        else arrivals?.laterArrivalAtEpochMillis.orEmpty()).distinct().sorted().firstOrNull {
        it > firstArrival && it - nowEpochMillis >= walkingWithBufferMillis
    }
    val nextState = if (status != AutoGuidanceStatus.NEXT_VEHICLE) null else when {
        recommended != null -> NextVehicleState.ETA_AVAILABLE
        input.furtherServiceEnded -> NextVehicleState.SERVICE_ENDED
        input.arrivalRequestFailed -> NextVehicleState.API_ERROR
        nextArrival != null -> NextVehicleState.LATER_VEHICLES_REQUIRED
        else -> NextVehicleState.ETA_PENDING
    }
    return AutoGuidanceAssessment(
        status = status,
        firstArrivalAtEpochMillis = firstArrival,
        nextArrivalAtEpochMillis = nextArrival,
        walkingSeconds = walking.durationSeconds,
        fastTravelSeconds = fastSeconds,
        bufferSeconds = input.bufferSeconds,
        arrivalFetchedAtEpochMillis = fetchedAt,
        fastSpeedKmh = policy.fastSpeedKmh,
        arrivalSource = if (!useTimetable) GuidanceArrivalSource.OFFICIAL_REALTIME
            else if (scheduled?.first?.source == OfficialTimetableEstimateSource.OFFICIAL_TIMETABLE_SCHEDULE)
                GuidanceArrivalSource.OFFICIAL_TIMETABLE_SCHEDULE else GuidanceArrivalSource.OFFICIAL_TIMETABLE_POSITION,
        arrivalPrecision = precision,
        firstArrivalLatestAtEpochMillis = firstLatest,
        estimateLabel = estimateLabel,
        nextVehicleState = nextState,
        recommendedArrivalAtEpochMillis = recommended,
        recommendedArrivalPrecision = if (useTimetable) ArrivalTimePrecision.MINUTES
            else arrivals?.nextArrivalPrecision ?: precision,
        boardingIdentity = boarding.identity
    )
}

/** One fixed arrival instant for display and both foreground/background movement decisions. */
internal fun OfficialTimetableEstimate.midpointArrivalAtEpochMillis(): Long =
    earliestArrivalAtEpochMillis + (latestArrivalAtEpochMillis-earliestArrivalAtEpochMillis)/2L

internal fun movementGuidanceTitle(assessment: AutoGuidanceAssessment, now: Long): String = when (assessment.status) {
    AutoGuidanceStatus.NEXT_VEHICLE -> "다음 차량을 기다리세요"
    AutoGuidanceStatus.FAST_WALK -> "뛰세요"
    AutoGuidanceStatus.WALK -> if (assessment.firstArrivalAtEpochMillis?.let {
        it-now-((assessment.walkingSeconds ?: 0).toLong()+assessment.bufferSeconds)*1000L >= 180_000L
    } == true) "여유 있어요" else "걸으세요"
    AutoGuidanceStatus.UNKNOWN -> "도착정보를 확인하고 있어요"
}

/** ETA availability changes the detail, never the decision that the first vehicle is too soon. */
fun nextVehicleGuidanceText(assessment: AutoGuidanceAssessment, now: Long): String {
    val at = assessment.recommendedArrivalAtEpochMillis
    if (assessment.nextVehicleState == NextVehicleState.ETA_AVAILABLE && at != null && at > now) {
        val seconds = ((at - now + 999) / 1000).coerceAtLeast(1)
        val minutes = if (assessment.arrivalSource == GuidanceArrivalSource.OFFICIAL_REALTIME)
            (seconds+59)/60 else ((seconds+30)/60).coerceAtLeast(1)
        val remaining = if (assessment.recommendedArrivalPrecision == ArrivalTimePrecision.MINUTES)
            "약 ${minutes}분" else "약 ${seconds / 60}분 ${seconds % 60}초"
        return "탑승 여유가 있는 다음 차량은 $remaining 뒤 도착 예정이에요."
    }
    return when (assessment.nextVehicleState) {
        NextVehicleState.SERVICE_ENDED -> "첫 차량은 현재 이동시간으로 탑승하기 어려워요. 공식 정보에서 추가 운행이 없음을 확인했어요."
        NextVehicleState.API_ERROR -> "첫 차량은 현재 이동시간으로 탑승하기 어려워요. 다음 차량 조회 중 오류가 발생해 다시 확인하고 있어요."
        NextVehicleState.LATER_VEHICLES_REQUIRED -> "현재 도보시간으로는 앞선 차량들의 탑승이 어려울 것으로 예상돼요. 이후 차량의 도착정보를 확인하고 있어요."
        else -> "첫 차량은 현재 이동시간으로 탑승하기 어려울 것으로 예상돼요. 다음 차량의 도착예정시간을 조회하고 있어요."
    }
}

private fun AutoGuidancePolicy.isValid() =
    fastSpeedKmh.isFinite() && fastSpeedKmh in 3.0..12.0 &&
        maxLocationAccuracyMeters.isFinite() && maxLocationAccuracyMeters >= 0.0 &&
        maxLocationAgeMillis > 0L && maxWalkingAgeMillis > 0L && maxArrivalAgeMillis > 0L && maxArrivalReceiptAgeMillis > 0L &&
        maxWalkingOriginDistanceMeters.isFinite() && maxWalkingOriginDistanceMeters >= 0.0 &&
        maxWalkingDestinationDistanceMeters.isFinite() && maxWalkingDestinationDistanceMeters >= 0.0

private fun isFresh(observedAt: Long, now: Long, maxAgeMillis: Long): Boolean =
    observedAt >= 0L && observedAt <= now && now - observedAt <= maxAgeMillis

private fun MapPoint.isValidCoordinate() =
    x.isFinite() && y.isFinite() && x in -180.0..180.0 && y in -90.0..90.0

private fun distanceMeters(first: MapPoint, second: MapPoint): Double {
    val latitudeDelta = Math.toRadians(second.y - first.y)
    val longitudeDelta = Math.toRadians(second.x - first.x)
    val a = sin(latitudeDelta / 2).let { it * it } +
        cos(Math.toRadians(first.y)) * cos(Math.toRadians(second.y)) *
        sin(longitudeDelta / 2).let { it * it }
    return 6_371_000.0 * 2 * asin(sqrt(a.coerceIn(0.0, 1.0)))
}

data class AutoVibrationState(
    val lastIdentity: OfficialBoardingIdentity? = null,
    val lastStatus: AutoGuidanceStatus? = null,
    val lastVibratedAtEpochMillis: Long? = null,
    val lastVehicleId: String? = null
)

data class AutoVibrationDecision(val shouldVibrate: Boolean, val state: AutoVibrationState)

/** Keep this state separately from the manual calculator's vibration action.
 * UNKNOWN preserves the last meaningful result, so a refresh failure then recovery cannot buzz twice.
 * A transition suppressed during the cooldown is consumed; later identical refreshes stay silent.
 */
fun decideAutoGuidanceVibration(
    previous: AutoVibrationState,
    consentEnabled: Boolean,
    identity: OfficialBoardingIdentity?,
    status: AutoGuidanceStatus,
    nowEpochMillis: Long,
    cooldownMillis: Long = 30_000L,
    vehicleId: String? = null
): AutoVibrationDecision {
    if (!consentEnabled || identity == null || !identity.isComplete ||
        status == AutoGuidanceStatus.UNKNOWN || nowEpochMillis < 0L || cooldownMillis < 0L
    ) {
        return AutoVibrationDecision(false, previous)
    }
    val changed = identity != previous.lastIdentity || status != previous.lastStatus ||
        vehicleId != previous.lastVehicleId
    val lastVibrated = previous.lastVibratedAtEpochMillis
    val cooldownElapsed = lastVibrated == null ||
        (lastVibrated >= 0L && nowEpochMillis >= lastVibrated &&
            nowEpochMillis - lastVibrated >= cooldownMillis)
    val shouldVibrate = changed && cooldownElapsed
    return AutoVibrationDecision(
        shouldVibrate = shouldVibrate,
        state = AutoVibrationState(
            lastIdentity = identity,
            lastStatus = status,
            lastVibratedAtEpochMillis = if (shouldVibrate) nowEpochMillis else lastVibrated,
            lastVehicleId = vehicleId
        )
    )
}

/** Shared by both tabs so one vehicle cannot trigger duplicate alerts across a tab switch. */
internal class AutomaticAlertGate {
    private var state = AutoVibrationState()
    fun claim(consent: Boolean, identity: OfficialBoardingIdentity?, vehicleId: String?,
        status: AutoGuidanceStatus, now: Long): Boolean {
        val decision = decideAutoGuidanceVibration(state, consent, identity, status, now,
            vehicleId = vehicleId)
        state = decision.state
        return decision.shouldVibrate
    }
}
