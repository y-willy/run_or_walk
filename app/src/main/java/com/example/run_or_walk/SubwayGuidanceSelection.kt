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
        it.estimate.midpointArrivalAtEpochMillis() > now }.sortedBy { it.estimate.midpointArrivalAtEpochMillis() }
    val first = estimates.firstOrNull() ?: return null
    val next = estimates.drop(1).firstOrNull { it.arrival.identity.directionId == first.arrival.identity.directionId &&
        it.estimate.trainNo != first.estimate.trainNo &&
            it.estimate.midpointArrivalAtEpochMillis() > first.estimate.midpointArrivalAtEpochMillis() }
    return TimetableArrivalEvidence(first.arrival.identity, first.estimate, next?.estimate,
        estimates.drop(1).map { it.estimate }.filter { it.trainNo != first.estimate.trainNo })
}

internal fun timetableArrivalText(evidence: TimetableArrivalEvidence, now: Long): String {
    return subwayEstimateText(evidence.first,now)
}

internal fun linkedSubwayEstimate(analysis: SubwayTimetableAnalysis?, arrival: OfficialSubwayArrival,
    now: Long): OfficialTimetableEstimate? = analysis?.estimates?.filter {
        it.arrival.identity == arrival.identity && it.arrival.trainId == arrival.trainId &&
            it.arrival.officialSlot == arrival.officialSlot && it.estimate.validUntilEpochMillis >= now &&
            it.estimate.midpointArrivalAtEpochMillis() > now
    }?.minByOrNull { it.estimate.midpointArrivalAtEpochMillis() }?.estimate ?: analysis?.estimates?.filter {
        it.estimate.source == OfficialTimetableEstimateSource.OFFICIAL_TIMETABLE_SCHEDULE &&
            it.arrival.identity.stopId == arrival.identity.stopId && it.arrival.identity.routeId == arrival.identity.routeId &&
            it.arrival.identity.directionId == arrival.identity.directionId && it.estimate.validUntilEpochMillis >= now &&
            it.estimate.midpointArrivalAtEpochMillis() > now
    }?.minByOrNull { it.estimate.midpointArrivalAtEpochMillis() }?.estimate

internal fun selectableSubwayArrivals(rows: List<OfficialSubwayArrival>, analysis: SubwayTimetableAnalysis?, now: Long): List<OfficialSubwayArrival> {
    val scheduled = analysis?.estimates.orEmpty().filter { it.estimate.source == OfficialTimetableEstimateSource.OFFICIAL_TIMETABLE_SCHEDULE &&
        it.estimate.validUntilEpochMillis >= now && it.estimate.midpointArrivalAtEpochMillis() > now &&
        rows.none { row -> row.identity.directionId == it.arrival.identity.directionId } }
        .groupBy { it.arrival.identity.directionId }.values.flatMap { group -> group.sortedBy { it.estimate.midpointArrivalAtEpochMillis() }.take(2) }
    return rows + scheduled.map { it.arrival }
}

/** Show both directions until one is selected; never hide numeric estimates behind position-only text. */
internal fun stationSubwayArrivalInformation(analysis: SubwayTimetableAnalysis?, now: Long): String? {
    analysis ?: return null
    val live = analysis.matchingArrivals.filter { it.snapshot.hasUsableOfficialEta(now) }
    val estimates = analysis.estimates.filter { it.estimate.validUntilEpochMillis >= now && it.estimate.midpointArrivalAtEpochMillis() > now }
    val directions = (live.map { it.identity.directionId } + estimates.map { it.arrival.identity.directionId }).distinct()
    return directions.mapNotNull { direction ->
        val real = live.filter { it.identity.directionId == direction }.minByOrNull { it.snapshot.firstArrivalAtEpochMillis }
        val candidates = estimates.filter { it.arrival.identity.directionId == direction }
        val estimated = candidates.filter { it.estimate.source == OfficialTimetableEstimateSource.OFFICIAL_TIMETABLE_POSITION }
            .ifEmpty { candidates }.minByOrNull { it.estimate.midpointArrivalAtEpochMillis() }
        val text = real?.let { officialEtaText(it.snapshot,now) }
            ?: estimated?.let { subwayEstimateText(it.estimate,now) } ?: return@mapNotNull null
        "$direction · $text"
    }.takeIf { it.isNotEmpty() }?.joinToString("\n")
}

internal fun subwayEstimateText(estimate: OfficialTimetableEstimate, now: Long): String {
    val remaining = estimate.midpointArrivalAtEpochMillis()-now
    val minutes = if (remaining <= 0L) 0L else ((remaining+30_000L)/60_000L).coerceAtLeast(1L)
    return "약 ${minutes}분 후"
}
