package com.example.run_or_walk

import java.time.LocalDate
import java.time.Instant
import java.time.ZoneId

enum class OfficialTrainPositionKind {
    /** The provider identifies an actual arrival event at sourceTimestampEpochMillis. */
    ARRIVAL_EVENT,
    /** The provider identifies an actual departure event at sourceTimestampEpochMillis. */
    DEPARTURE_EVENT,
    /** The train is confirmed currently at this stop, within its scheduled dwell interval. */
    AT_STATION,
    /**
     * This is the last confirmed station; progress may extend to the next scheduled stop.
     * An adapter may use a named official position message (including arvlCd=99) after it has
     * proved the exact train and named stop. A station count alone never establishes this.
     */
    POSITION_REPORTED,
    UNKNOWN
}

data class OfficialTrainPositionObservation(
    val authority: String,
    val trainNo: String,
    val serviceDate: LocalDate,
    val dayType: OfficialServiceDayType,
    val routeId: String,
    val directionId: String,
    val serviceVariant: String,
    val positionStopId: String?,
    val positionStopName: String?,
    val positionKind: OfficialTrainPositionKind,
    /** Original provider timestamp. Receipt and device time must never replace it. */
    val sourceTimestampEpochMillis: Long,
    val receivedAtEpochMillis: Long,
    val routePatternId: String? = null
)

enum class OfficialTimetableEstimateSource { OFFICIAL_TIMETABLE_POSITION, OFFICIAL_TIMETABLE_SCHEDULE }

/** A schedule-based interval, intentionally unrelated to RealtimeArrivalSnapshot. */
data class OfficialTimetableEstimate(
    val identity: OfficialBoardingIdentity,
    val trainNo: String,
    val serviceDate: LocalDate,
    val dayType: OfficialServiceDayType,
    val operationType: OfficialTrainOperationType,
    val routePatternId: String,
    val positionStopId: String,
    val targetSequence: Int,
    val scheduledTargetArrivalAtEpochMillis: Long,
    val earliestArrivalAtEpochMillis: Long,
    val latestArrivalAtEpochMillis: Long,
    val minimumObservedDelayMillis: Long,
    val maximumObservedDelayMillis: Long,
    val sourceTimestampEpochMillis: Long,
    val receivedAtEpochMillis: Long,
    val observationAgeMillis: Long,
    val validUntilEpochMillis: Long,
    val timetableSource: OfficialTimetableSource,
    val calendarSourceVersion: String,
    val source: OfficialTimetableEstimateSource = OfficialTimetableEstimateSource.OFFICIAL_TIMETABLE_POSITION
) {
    val isRealtime: Boolean = false
    val scheduleValidityUnconfirmed: Boolean =
        timetableSource.validFromServiceDate == null || timetableSource.validThroughServiceDate == null
    val label: String = (if (source == OfficialTimetableEstimateSource.OFFICIAL_TIMETABLE_POSITION)
        "공식 시간표·열차 위치 기반 추정 · " else "공식 시간표 기준 · 실시간 위치 미확인 · ") +
        (timetableSource.scheduleAsOfDate ?: Instant.ofEpochMilli(timetableSource.publishedAtEpochMillis)
            .atZone(ZoneId.of(timetableSource.timeZone)).toLocalDate()) + " 기준" +
        if (scheduleValidityUnconfirmed) " · 유효기간 미확인" else ""
}

enum class OfficialTimetableUnknownReason {
    INVALID_INPUT, INVALID_TIMETABLE, SOURCE_NOT_YET_PUBLISHED, OUTSIDE_VALIDITY,
    CALENDAR_UNCONFIRMED, IDENTITY_MISMATCH, OBSERVATION_FUTURE, OBSERVATION_STALE,
    TRAIN_NOT_FOUND, AMBIGUOUS_TRAIN, POSITION_UNKNOWN, POSITION_NOT_FOUND, AMBIGUOUS_POSITION,
    POSITION_CONFLICT, TARGET_NOT_SERVED, TARGET_NOT_AHEAD, AMBIGUOUS_TARGET,
    ANCHOR_TIME_UNAVAILABLE, DELAY_OUT_OF_RANGE, ESTIMATE_PASSED
}

sealed interface OfficialTimetableEstimateResult {
    data class Estimated(val estimate: OfficialTimetableEstimate) : OfficialTimetableEstimateResult
    data class Unknown(val reason: OfficialTimetableUnknownReason) : OfficialTimetableEstimateResult
}

data class OfficialTimetableEstimationPolicy(
    val maxObservationAgeMillis: Long = 300_000L,
    val maxEarlyRunningMillis: Long = 120_000L,
    val maxLateRunningMillis: Long = 1_800_000L
)

/**
 * Exact trip/route/service matching precedes all position matching. No train number stripping,
 * branch inference, station-count conversion, holiday inference or realtime ETA substitution.
 * POSITION_REPORTED covers the scheduled interval from this stop to the next actual call;
 * AT_STATION covers its dwell. The target's scheduled arrival is shifted by those bounds.
 * Absolute ETA stays fixed as time passes, so countdowns naturally subtract source age.
 * These bounds describe a schedule assumption, not a guarantee against future disruption.
 */
fun estimateOfficialTimetableArrival(
    timetable: OfficialTimetable,
    observation: OfficialTrainPositionObservation,
    target: OfficialBoardingIdentity,
    nowEpochMillis: Long,
    policy: OfficialTimetableEstimationPolicy = OfficialTimetableEstimationPolicy()
): OfficialTimetableEstimateResult {
    fun unknown(reason: OfficialTimetableUnknownReason) = OfficialTimetableEstimateResult.Unknown(reason)
    if (nowEpochMillis < 0 || policy.maxObservationAgeMillis <= 0 ||
        policy.maxEarlyRunningMillis < 0 || policy.maxLateRunningMillis < 0 ||
        policy.maxObservationAgeMillis > Long.MAX_VALUE - nowEpochMillis ||
        !target.isComplete || !listOf(observation.authority, observation.trainNo, observation.routeId,
            observation.directionId, observation.serviceVariant).all(::validTimetableText) ||
        observation.sourceTimestampEpochMillis < 0 || observation.receivedAtEpochMillis < 0 ||
        (observation.routePatternId != null && !validTimetableText(observation.routePatternId)) ||
        (observation.positionStopId != null && !validTimetableText(observation.positionStopId)) ||
        (observation.positionStopName != null && !validTimetableText(observation.positionStopName))) {
        return unknown(OfficialTimetableUnknownReason.INVALID_INPUT)
    }
    if (!timetable.isStructurallyValid()) return unknown(OfficialTimetableUnknownReason.INVALID_TIMETABLE)
    if (timetable.source.publishedAtEpochMillis > nowEpochMillis) {
        return unknown(OfficialTimetableUnknownReason.SOURCE_NOT_YET_PUBLISHED)
    }
    if ((timetable.source.validFromServiceDate?.let { observation.serviceDate < it } == true) ||
        (timetable.source.validThroughServiceDate?.let { observation.serviceDate > it } == true)) {
        return unknown(OfficialTimetableUnknownReason.OUTSIDE_VALIDITY)
    }
    val calendarDay = timetable.calendar.singleOrNull { it.serviceDate == observation.serviceDate }
    if (calendarDay?.dayType != observation.dayType) {
        return unknown(OfficialTimetableUnknownReason.CALENDAR_UNCONFIRMED)
    }
    if (target.authority != timetable.identityAuthority || target.authority != observation.authority ||
        target.routeId != observation.routeId || target.directionId != observation.directionId ||
        target.serviceVariant != observation.serviceVariant) {
        return unknown(OfficialTimetableUnknownReason.IDENTITY_MISMATCH)
    }
    if (observation.sourceTimestampEpochMillis > observation.receivedAtEpochMillis ||
        observation.receivedAtEpochMillis > nowEpochMillis) {
        return unknown(OfficialTimetableUnknownReason.OBSERVATION_FUTURE)
    }
    val age = nowEpochMillis - observation.sourceTimestampEpochMillis
    if (age > policy.maxObservationAgeMillis) return unknown(OfficialTimetableUnknownReason.OBSERVATION_STALE)
    val trips = timetable.trips.filter {
        it.trainNo == observation.trainNo && it.serviceDate == observation.serviceDate &&
            it.dayType == observation.dayType && it.routeId == observation.routeId &&
            it.directionId == observation.directionId && it.serviceVariant == observation.serviceVariant &&
            (observation.routePatternId == null || it.routePatternId == observation.routePatternId)
    }
    if (trips.isEmpty()) return unknown(OfficialTimetableUnknownReason.TRAIN_NOT_FOUND)
    if (trips.size != 1) return unknown(OfficialTimetableUnknownReason.AMBIGUOUS_TRAIN)
    val trip = trips.single()
    if (observation.positionKind == OfficialTrainPositionKind.UNKNOWN ||
        (observation.positionStopId == null && observation.positionStopName == null)) {
        return unknown(OfficialTimetableUnknownReason.POSITION_UNKNOWN)
    }
    // ID is primary. A supplied name must agree with that exact call; no aliases are invented.
    val positions = trip.stops.withIndex().filter {
        if (observation.positionStopId != null) it.value.stopId == observation.positionStopId
        else it.value.stopName == observation.positionStopName
    }
    if (positions.isEmpty()) return unknown(OfficialTimetableUnknownReason.POSITION_NOT_FOUND)
    if (positions.size != 1) return unknown(OfficialTimetableUnknownReason.AMBIGUOUS_POSITION)
    val (positionIndex, position) = positions.single()
    if (observation.positionStopName != null && position.stopName != observation.positionStopName) {
        return unknown(OfficialTimetableUnknownReason.POSITION_CONFLICT)
    }
    val targets = trip.stops.withIndex().filter { it.value.stopId == target.stopId }
    if (targets.isEmpty()) return unknown(OfficialTimetableUnknownReason.TARGET_NOT_SERVED)
    if (targets.size != 1) return unknown(OfficialTimetableUnknownReason.AMBIGUOUS_TARGET)
    val (targetIndex, targetStop) = targets.single()
    if (targetIndex <= positionIndex) return unknown(OfficialTimetableUnknownReason.TARGET_NOT_AHEAD)
    val scheduledTarget = targetStop.arrivalAtEpochMillis
        ?: return unknown(OfficialTimetableUnknownReason.ANCHOR_TIME_UNAVAILABLE)
    val anchorStart: Long?
    val anchorEnd: Long?
    when (observation.positionKind) {
        OfficialTrainPositionKind.ARRIVAL_EVENT -> {
            anchorStart = position.arrivalAtEpochMillis
            anchorEnd = anchorStart
        }
        OfficialTrainPositionKind.DEPARTURE_EVENT -> {
            anchorStart = position.departureAtEpochMillis
            anchorEnd = anchorStart
        }
        OfficialTrainPositionKind.AT_STATION -> {
            anchorStart = position.arrivalAtEpochMillis
            anchorEnd = position.departureAtEpochMillis
        }
        OfficialTrainPositionKind.POSITION_REPORTED -> {
            anchorStart = position.arrivalAtEpochMillis ?: position.departureAtEpochMillis
            anchorEnd = trip.stops.getOrNull(positionIndex + 1)?.arrivalAtEpochMillis
        }
        OfficialTrainPositionKind.UNKNOWN -> return unknown(OfficialTimetableUnknownReason.POSITION_UNKNOWN)
    }
    if (anchorStart == null || anchorEnd == null || anchorEnd < anchorStart || anchorEnd > scheduledTarget) {
        return unknown(OfficialTimetableUnknownReason.ANCHOR_TIME_UNAVAILABLE)
    }
    val minimumDelay = observation.sourceTimestampEpochMillis - anchorEnd
    val maximumDelay = observation.sourceTimestampEpochMillis - anchorStart
    // A position interval can span several minutes even for an on-time train. Reject only
    // when no point in that interval is compatible with the configured running-delay policy;
    // retain the complete interval rather than narrowing it to a more optimistic estimate.
    if (maximumDelay < -policy.maxEarlyRunningMillis || minimumDelay > policy.maxLateRunningMillis) {
        return unknown(OfficialTimetableUnknownReason.DELAY_OUT_OF_RANGE)
    }
    // Offset arithmetic avoids adding a possibly negative schedule delay to an epoch instant.
    val earliestOffset = scheduledTarget - anchorEnd
    val latestOffset = scheduledTarget - anchorStart
    if (latestOffset > Long.MAX_VALUE - observation.sourceTimestampEpochMillis) {
        return unknown(OfficialTimetableUnknownReason.INVALID_INPUT)
    }
    val earliest = observation.sourceTimestampEpochMillis + earliestOffset
    val latest = observation.sourceTimestampEpochMillis + latestOffset
    if (latest < nowEpochMillis) return unknown(OfficialTimetableUnknownReason.ESTIMATE_PASSED)
    return OfficialTimetableEstimateResult.Estimated(OfficialTimetableEstimate(
        target, trip.trainNo, trip.serviceDate, trip.dayType, trip.operationType, trip.routePatternId,
        position.stopId, targetStop.sequence, scheduledTarget, earliest, latest, minimumDelay, maximumDelay,
        observation.sourceTimestampEpochMillis, observation.receivedAtEpochMillis, age,
        minOf(observation.sourceTimestampEpochMillis + policy.maxObservationAgeMillis, latest),
        timetable.source, requireNotNull(calendarDay).sourceVersion
    ))
}
