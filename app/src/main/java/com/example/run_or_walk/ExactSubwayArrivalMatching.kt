package com.example.run_or_walk

/**
 * The caller must obtain [verifiedIdentity] from an explicit official boarding confirmation
 * or an independently verified provider mapping. A station name, Kakao place ID, coordinate
 * or an unverified direction label cannot create this binding.
 *
 * Select the earliest actual vehicle only after matching station, line, direction and exact
 * destination/service variant. Ambiguous observations never resolve by input ordering.
 */
internal fun chooseMatchingSubwayArrival(
    verifiedIdentity: OfficialBoardingIdentity?,
    arrivals: List<OfficialSubwayArrival>,
    nowEpochMillis: Long
): OfficialSubwayArrival? {
    if (verifiedIdentity == null || !verifiedIdentity.isComplete || nowEpochMillis < 0L) return null
    val exact = arrivals.filter {
        it.identity == verifiedIdentity && it.snapshot.identity == verifiedIdentity
    }
    val candidates = exact.groupBy { it.trainId.ifBlank { it.snapshot.vehicleIdentityFallback ?: "eta:${it.snapshot.firstArrivalAtEpochMillis}" } }.values.mapNotNull { observations ->
        // Repeated identical rows are harmless; conflicting rows for one vehicle are uncertain.
        observations.distinct().singleOrNull()
    }.filter { arrival ->
        val snapshot = arrival.snapshot
        snapshot.reliable && snapshot.trustState == ArrivalTrustState.VALID &&
            snapshot.validForMillis > 0L && snapshot.fetchedAtEpochMillis >= 0L &&
            snapshot.fetchedAtEpochMillis <= nowEpochMillis &&
            nowEpochMillis - snapshot.fetchedAtEpochMillis <= minOf(snapshot.validForMillis, 600_000L) &&
            snapshot.firstArrivalAtEpochMillis >= nowEpochMillis &&
            (snapshot.receivedAtEpochMillis == null || nowEpochMillis - snapshot.receivedAtEpochMillis <= 60_000L) &&
            (snapshot.nextArrivalAtEpochMillis == null ||
                snapshot.nextArrivalAtEpochMillis > snapshot.firstArrivalAtEpochMillis)
    }
    val firstTime = candidates.minOfOrNull { it.snapshot.firstArrivalAtEpochMillis } ?: return null
    return candidates.filter { it.snapshot.firstArrivalAtEpochMillis == firstTime }.singleOrNull()
}
