package com.example.run_or_walk

internal data class MatchedGyeonggiArrival(val vehicleId: String, val snapshot: RealtimeArrivalSnapshot)

/** The screen and foreground service use the same official stop/route/order and expiry checks. */
internal fun chooseMatchingGyeonggiArrival(
    match: GyeonggiOfficialMatch,
    arrivals: List<GyeonggiBusArrivalDisplay>,
    nowMillis: Long
): MatchedGyeonggiArrival? {
    if (!match.isValid() || nowMillis < 0) return null
    // Trust positive official ETAs for the exact station, route and boarding order.
    // GBIS may omit vehicle IDs or report minute-precision ETAs.
    val rows = arrivals.filter {
        it.stationId == match.stationId && it.routeId == match.routeId && it.staOrder == match.staOrder &&
            it.trust == ArrivalTrustState.VALID && it.seconds != null && it.seconds in 1..86_400 &&
            it.observedAtMillis != null && nowMillis - it.observedAtMillis in 0L..60_000L &&
            it.observedAtMillis + it.seconds * 1_000L > nowMillis
    }.sortedBy { requireNotNull(it.observedAtMillis) + requireNotNull(it.seconds) * 1_000L }
    val first = rows.firstOrNull() ?: return null
    val next = rows.drop(1).firstOrNull {
        requireNotNull(it.observedAtMillis) + requireNotNull(it.seconds) * 1_000L >
            requireNotNull(first.observedAtMillis) + requireNotNull(first.seconds) * 1_000L
    }
    val observed = requireNotNull(first.observedAtMillis)
    val expiresAt = minOf(observed + 60_000L, next?.observedAtMillis?.plus(60_000L) ?: Long.MAX_VALUE)
    return MatchedGyeonggiArrival(first.vehicleId, RealtimeArrivalSnapshot(match.identity,
        observed + requireNotNull(first.seconds) * 1_000L,
        next?.let { requireNotNull(it.observedAtMillis) + requireNotNull(it.seconds) * 1_000L },
        observed, expiresAt - observed))
}
