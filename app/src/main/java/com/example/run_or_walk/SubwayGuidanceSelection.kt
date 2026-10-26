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
    val minimum = ((evidence.first.earliestArrivalAtEpochMillis - now).coerceAtLeast(0) + 59_999L) / 60_000L
    val maximum = ((evidence.first.latestArrivalAtEpochMillis - now).coerceAtLeast(0) + 59_999L) / 60_000L
    return if (minimum == maximum) "시간표 추정 약 ${minimum}분" else "시간표 추정 약 ${minimum}~${maximum}분"
}
