package com.example.run_or_walk

internal data class MatchedGyeonggiArrival(val vehicleId: String, val snapshot: RealtimeArrivalSnapshot)

/** The screen and foreground service use the same official stop/route/order and expiry checks. */
internal fun chooseMatchingGyeonggiArrival(
    match: GyeonggiOfficialMatch,
    arrivals: List<GyeonggiBusArrivalDisplay>,
    nowMillis: Long
): MatchedGyeonggiArrival? {
    if (!match.isValid() || nowMillis < 0) return null
    val matching = arrivals.filter {
        it.stationId == match.stationId && it.routeId == match.routeId && it.staOrder == match.staOrder
    }.distinct()
    // Conflicting rows for one vehicle or one arrival sequence cannot be resolved by list order.
    if (matching.groupBy { it.vehicleId }.values.any { it.size > 1 } ||
        matching.groupBy { it.sequence }.values.any { it.size > 1 }) return null
    val rows = matching.filter {
        it.sequence in 1..2 && it.vehicleId.matches(Regex("[1-9][0-9]+")) &&
            it.trust == ArrivalTrustState.VALID && it.seconds != null && it.seconds in 1..86_400 &&
            it.observedAtMillis != null && it.observedAtMillis <= it.fetchedAtMillis &&
            it.fetchedAtMillis <= nowMillis && nowMillis - it.observedAtMillis in 0L..60_000L &&
            it.observedAtMillis + it.seconds * 1_000L > nowMillis &&
            (it.routeName.isBlank() || it.routeName == match.routeName) &&
            (it.destination.isBlank() || it.destination == match.destination)
    }.sortedBy { requireNotNull(it.observedAtMillis) + requireNotNull(it.seconds) * 1_000L }
    val first = rows.firstOrNull() ?: return null
    val next = rows.drop(1).firstOrNull {
        it.vehicleId != first.vehicleId && requireNotNull(it.observedAtMillis) + requireNotNull(it.seconds) * 1_000L >
            requireNotNull(first.observedAtMillis) + requireNotNull(first.seconds) * 1_000L
    }
    val observed = requireNotNull(first.observedAtMillis)
    val expiresAt = minOf(observed + 60_000L, next?.observedAtMillis?.plus(60_000L) ?: Long.MAX_VALUE)
    return MatchedGyeonggiArrival(first.vehicleId, RealtimeArrivalSnapshot(match.identity,
        observed + requireNotNull(first.seconds) * 1_000L,
        next?.let { requireNotNull(it.observedAtMillis) + requireNotNull(it.seconds) * 1_000L },
        observed, expiresAt - observed))
}
