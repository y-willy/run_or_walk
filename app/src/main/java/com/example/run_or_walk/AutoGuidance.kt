package com.example.run_or_walk

import kotlin.math.asin
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

enum class AutoGuidanceStatus { WALK, FAST_WALK, NEXT_TRAIN, UNKNOWN }

/** Source-data trust, distinct from transport success and boarding identity matching. */
enum class ArrivalTrustState { VALID, STALE, FUTURE_TIMESTAMP, INVALID_ETA, UNKNOWN }

enum class AutoGuidanceUnknownReason {
    LOCATION_PERMISSION_REQUIRED, LOCATION_UNAVAILABLE, LOCATION_INACCURATE, LOCATION_STALE,
    BOARDING_UNCONFIRMED,
    ARRIVAL_UNAVAILABLE, ARRIVAL_FAILED, ARRIVAL_MISMATCH, ARRIVAL_STALE, ARRIVAL_UNCERTAIN,
    ARRIVAL_FUTURE_TIMESTAMP, ARRIVAL_INVALID_ETA, NEXT_ARRIVAL_UNAVAILABLE, NO_CATCHABLE_TRAIN,
    WALKING_UNAVAILABLE, WALKING_STALE, WALKING_MISMATCH, INVALID_INPUT
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
    val observedAtEpochMillis: Long
)

/** A real walking response, tied to the coordinates and official boarding selection queried. */
data class GuidanceWalkingRoute(
    val origin: MapPoint,
    val destination: MapPoint,
    val boardingIdentity: OfficialBoardingIdentity,
    val durationSeconds: Int,
    val fetchedAtEpochMillis: Long,
    val validForMillis: Long
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
    val trustState: ArrivalTrustState = ArrivalTrustState.VALID
)

data class AutoGuidanceInput(
    val location: GuidanceLocation?,
    val boarding: GuidanceBoarding?,
    val walkingRoute: GuidanceWalkingRoute?,
    val arrivals: RealtimeArrivalSnapshot?,
    val locationPermissionGranted: Boolean,
    val arrivalRequestFailed: Boolean = false,
    val bufferSeconds: Int = 120
)

/** Conservative defaults; the adapter must use a shorter validity when its provider requires it. */
data class AutoGuidancePolicy(
    val fastTravelRatio: Double = 0.75,
    val maxLocationAccuracyMeters: Double = 50.0,
    val maxLocationAgeMillis: Long = 30_000L,
    val maxWalkingAgeMillis: Long = 120_000L,
    val maxArrivalAgeMillis: Long = 60_000L,
    val maxWalkingOriginDistanceMeters: Double = 35.0,
    val maxWalkingDestinationDistanceMeters: Double = 15.0
)

data class AutoGuidanceAssessment(
    val status: AutoGuidanceStatus,
    val unknownReason: AutoGuidanceUnknownReason? = null,
    val firstArrivalAtEpochMillis: Long? = null,
    val nextArrivalAtEpochMillis: Long? = null,
    val walkingSeconds: Int? = null,
    /** A fast-walking estimate, calculated as ceil(walkingSeconds * fastTravelRatio). */
    val fastTravelSeconds: Int? = null,
    val bufferSeconds: Int = 120,
    val arrivalFetchedAtEpochMillis: Long? = null,
    val fastTravelRatio: Double = 0.75
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
    fun unknown(reason: AutoGuidanceUnknownReason) = AutoGuidanceAssessment(
        status = AutoGuidanceStatus.UNKNOWN,
        unknownReason = reason,
        bufferSeconds = input.bufferSeconds,
        fastTravelRatio = policy.fastTravelRatio
    )

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
    // An error invalidates any older successful response retained by the UI.
    if (input.arrivalRequestFailed) return unknown(AutoGuidanceUnknownReason.ARRIVAL_FAILED)
    val arrivals = input.arrivals
        ?: return unknown(AutoGuidanceUnknownReason.ARRIVAL_UNAVAILABLE)
    if (arrivals.identity != boarding.identity) {
        return unknown(AutoGuidanceUnknownReason.ARRIVAL_MISMATCH)
    }
    when (arrivals.trustState) {
        ArrivalTrustState.STALE -> return unknown(AutoGuidanceUnknownReason.ARRIVAL_STALE)
        ArrivalTrustState.FUTURE_TIMESTAMP -> return unknown(AutoGuidanceUnknownReason.ARRIVAL_FUTURE_TIMESTAMP)
        ArrivalTrustState.INVALID_ETA -> return unknown(AutoGuidanceUnknownReason.ARRIVAL_INVALID_ETA)
        ArrivalTrustState.UNKNOWN -> return unknown(AutoGuidanceUnknownReason.ARRIVAL_UNCERTAIN)
        ArrivalTrustState.VALID -> Unit
    }
    if (!arrivals.reliable) return unknown(AutoGuidanceUnknownReason.ARRIVAL_UNCERTAIN)
    if (arrivals.fetchedAtEpochMillis > nowEpochMillis) {
        return unknown(AutoGuidanceUnknownReason.ARRIVAL_FUTURE_TIMESTAMP)
    }
    if (arrivals.validForMillis <= 0L ||
        !isFresh(arrivals.fetchedAtEpochMillis, nowEpochMillis,
            minOf(arrivals.validForMillis, policy.maxArrivalAgeMillis)) ||
        arrivals.firstArrivalAtEpochMillis < nowEpochMillis
    ) {
        return unknown(AutoGuidanceUnknownReason.ARRIVAL_STALE)
    }
    if (arrivals.nextArrivalAtEpochMillis != null &&
        arrivals.nextArrivalAtEpochMillis <= arrivals.firstArrivalAtEpochMillis
    ) {
        return unknown(AutoGuidanceUnknownReason.ARRIVAL_UNCERTAIN)
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

    val fastSeconds = ceil(walking.durationSeconds * policy.fastTravelRatio).toInt()
    val remainingMillis = arrivals.firstArrivalAtEpochMillis - nowEpochMillis
    val walkingWithBufferMillis = (walking.durationSeconds.toLong() + input.bufferSeconds) * 1_000L
    val fastWithBufferMillis = (fastSeconds.toLong() + input.bufferSeconds) * 1_000L
    val status = when {
        remainingMillis >= walkingWithBufferMillis -> AutoGuidanceStatus.WALK
        remainingMillis >= fastWithBufferMillis -> AutoGuidanceStatus.FAST_WALK
        arrivals.nextArrivalAtEpochMillis == null ->
            return unknown(AutoGuidanceUnknownReason.NEXT_ARRIVAL_UNAVAILABLE)
        arrivals.nextArrivalAtEpochMillis - nowEpochMillis >= walkingWithBufferMillis ->
            AutoGuidanceStatus.NEXT_TRAIN
        else -> return unknown(AutoGuidanceUnknownReason.NO_CATCHABLE_TRAIN)
    }
    return AutoGuidanceAssessment(
        status = status,
        firstArrivalAtEpochMillis = arrivals.firstArrivalAtEpochMillis,
        nextArrivalAtEpochMillis = arrivals.nextArrivalAtEpochMillis,
        walkingSeconds = walking.durationSeconds,
        fastTravelSeconds = fastSeconds,
        bufferSeconds = input.bufferSeconds,
        arrivalFetchedAtEpochMillis = arrivals.fetchedAtEpochMillis,
        fastTravelRatio = policy.fastTravelRatio
    )
}

private fun AutoGuidancePolicy.isValid() =
    fastTravelRatio.isFinite() && fastTravelRatio > 0.0 && fastTravelRatio <= 1.0 &&
        maxLocationAccuracyMeters.isFinite() && maxLocationAccuracyMeters >= 0.0 &&
        maxLocationAgeMillis > 0L && maxWalkingAgeMillis > 0L && maxArrivalAgeMillis > 0L &&
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
