package com.example.run_or_walk

/** Only rows already proven to reach the requested alighting station may be combined. */
internal fun selectDestinationSubwayArrival(analysis: SubwayTimetableAnalysis?, now: Long): OfficialSubwayArrival? {
    if (analysis?.directionVerified != true) return null
    val rows = analysis.matchingArrivals.filter { it.snapshot.hasUsableOfficialEta(now) }
        .sortedBy { it.snapshot.firstArrivalAtEpochMillis }
    val first = rows.firstOrNull() ?: return null
    val next = rows.drop(1).firstOrNull {
        it.identity.directionId == first.identity.directionId &&
            (it.trainId.isBlank() || first.trainId.isBlank() || it.trainId != first.trainId) &&
            it.snapshot.firstArrivalAtEpochMillis > first.snapshot.firstArrivalAtEpochMillis
    }
    val later = rows.filter { it.snapshot.firstArrivalAtEpochMillis > first.snapshot.firstArrivalAtEpochMillis }
        .distinctBy { it.trainId.ifBlank { "slot:${it.officialSlot}" } }
        .filter { first.trainId.isBlank() || it.trainId != first.trainId }
        .map { it.snapshot.firstArrivalAtEpochMillis }.distinct().sorted()
    return first.copy(snapshot = first.snapshot.copy(nextArrivalAtEpochMillis = next?.snapshot?.firstArrivalAtEpochMillis,
        laterArrivalAtEpochMillis = later))
}

internal fun selectTimetableEvidence(analysis: SubwayTimetableAnalysis?, now: Long): TimetableArrivalEvidence? {
    if (analysis?.directionVerified != true) return null
    val estimates = analysis.estimates.filter { now <= it.estimate.validUntilEpochMillis &&
        it.estimate.latestArrivalAtEpochMillis > now }.sortedBy { it.estimate.earliestArrivalAtEpochMillis }
    val first = estimates.firstOrNull() ?: return null
    val next = estimates.drop(1).firstOrNull { it.arrival.identity.directionId == first.arrival.identity.directionId &&
        it.estimate.trainNo != first.estimate.trainNo &&
        it.estimate.earliestArrivalAtEpochMillis > first.estimate.earliestArrivalAtEpochMillis }
    return TimetableArrivalEvidence(first.arrival.identity, first.estimate, next?.estimate,
        estimates.drop(1).map { it.estimate }.filter { it.trainNo != first.estimate.trainNo })
}

internal fun timetableArrivalText(evidence: TimetableArrivalEvidence, now: Long): String {
    return subwayEstimateText(evidence.first,now)
}

internal fun subwayEstimateText(estimate: OfficialTimetableEstimate, now: Long): String {
    if (estimate.source == OfficialTimetableEstimateSource.OFFICIAL_TIMETABLE_SCHEDULE) {
        val minutes = ((estimate.scheduledTargetArrivalAtEpochMillis-now).coerceAtLeast(0L)+59_999L)/60_000L
        return "시간표 기반 추정 · 약 ${minutes}분 후"
    }
    val minimum = ((estimate.earliestArrivalAtEpochMillis-now).coerceAtLeast(0)+59_999L)/60_000L
    val maximum = ((estimate.latestArrivalAtEpochMillis-now).coerceAtLeast(0)+59_999L)/60_000L
    return "열차 위치·시간표 기반 추정 · 약 " + if (minimum==maximum) "${minimum}분 후" else "${minimum}~${maximum}분 후"
}
