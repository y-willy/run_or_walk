package com.example.run_or_walk

internal data class MatchedGyeonggiArrival(val vehicleId: String, val snapshot: RealtimeArrivalSnapshot)

/** The ETA uses receipt time only when GBIS omitted its own timestamp. */
internal fun GyeonggiBusArrivalDisplay.arrivalBasisMillis(nowMillis: Long): Long =
    officialBusEffectiveObservedAt(observedAtMillis, fetchedAtMillis, normalizedAtMillis ?: nowMillis)

internal fun isUsableGyeonggiArrival(row: GyeonggiBusArrivalDisplay, nowMillis: Long): Boolean =
    row.trust == ArrivalTrustState.VALID && officialBusArrivalTrust(
        row.seconds?.toLong(), row.observedAtMillis, row.fetchedAtMillis, nowMillis
    ) == ArrivalTrustState.VALID &&
        nowMillis <= officialBusArrivalExpiresAt(row.observedAtMillis,row.fetchedAtMillis,row.normalizedAtMillis ?: nowMillis) &&
        row.arrivalBasisMillis(nowMillis) + requireNotNull(row.seconds) * 1_000L > nowMillis

/** A missing response order does not erase the independently verified boarding occurrence. */
internal fun GyeonggiBusArrivalDisplay.matchesOfficialMatch(match: GyeonggiOfficialMatch): Boolean =
    stationId == match.stationId && routeId == match.routeId &&
        (staOrder.isBlank() || staOrder == match.staOrder)

/** The screen and foreground service share official identity, timing and next-slot checks. */
internal fun chooseMatchingGyeonggiArrival(
    match: GyeonggiOfficialMatch,
    arrivals: List<GyeonggiBusArrivalDisplay>,
    nowMillis: Long
): MatchedGyeonggiArrival? {
    if (!match.isValid() || nowMillis < 0) return null
    val rows = arrivals.filter { it.matchesOfficialMatch(match) && isUsableGyeonggiArrival(it, nowMillis) }
        .distinct().mapIndexed { index, row ->
            if (row.sequence in 1..2) row else row.copy(sequence = index + 1)
        }.sortedWith(compareBy<GyeonggiBusArrivalDisplay> { it.sequence }
            .thenBy { it.arrivalBasisMillis(nowMillis) + requireNotNull(it.seconds) * 1_000L })
    val first = rows.firstOrNull() ?: return null
    val basis = first.arrivalBasisMillis(nowMillis)
    val firstArrival = basis + requireNotNull(first.seconds) * 1_000L
    val next = rows.drop(1).firstOrNull {
        it.sequence != first.sequence &&
            (first.vehicleId.isBlank() || it.vehicleId.isBlank() || it.vehicleId != first.vehicleId) &&
            it.arrivalBasisMillis(nowMillis) + requireNotNull(it.seconds) * 1_000L > firstArrival
    }
    val expiresAt = officialBusArrivalExpiresAt(first.observedAtMillis, first.fetchedAtMillis,
        first.normalizedAtMillis ?: nowMillis)
    return MatchedGyeonggiArrival(first.vehicleId, RealtimeArrivalSnapshot(match.identity,
        firstArrival,
        next?.let { it.arrivalBasisMillis(nowMillis) + requireNotNull(it.seconds) * 1_000L },
        basis, expiresAt - basis,
        etaPrecision = first.etaPrecision,
        sourceTimestampAvailable = first.sourceTimestampAvailable,
        receivedAtEpochMillis = first.fetchedAtMillis,
        vehicleIdentityFallback = "official-slot:${first.sequence}".takeIf { first.vehicleId.isBlank() },
        nextArrivalPrecision = next?.etaPrecision,
        nextArrivalValidUntilEpochMillis = next?.let {
            officialBusArrivalExpiresAt(it.observedAtMillis,it.fetchedAtMillis,it.normalizedAtMillis ?: nowMillis)
        }))
}
