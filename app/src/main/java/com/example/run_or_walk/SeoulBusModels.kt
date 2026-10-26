package com.example.run_or_walk

/** IDs belong to Seoul bus APIs. Kakao place IDs and the five-digit ARS code are not stopId. */
data class OfficialSeoulBusStop(
    val stopId: String,
    val arsId: String,
    val name: String,
    val point: MapPoint? = null
)

/** The official stop sequence distinguishes a route's opposite direction and loop occurrences. */
data class OfficialSeoulBusRoute(
    val stopId: String,
    val routeId: String,
    val name: String,
    val stopOrder: String,
    val direction: String,
    val routeType: String = "",
    val supported: Boolean = true
) {
    // `dir` in arrivals and `adirection` in station metadata are presentation aliases.
    // The route's verified sequence, not either text label, identifies this occurrence.
    val identity: OfficialBoardingIdentity
        get() = OfficialBoardingIdentity("seoul-bus", stopId, routeId, stopOrder, routeId)
}

data class OfficialSeoulBusArrival(
    val identity: OfficialBoardingIdentity,
    val vehicleId: String,
    val routeName: String,
    val directionLabel: String,
    val snapshot: RealtimeArrivalSnapshot,
    val officialArrivalMessage: String = "",
    val isFull: Boolean? = null,
    val isLast: Boolean? = null,
    val statusMessage: String = "",
    val gatewayFetchedAtEpochMillis: Long? = null,
    val issues: Set<String> = emptySet()
)

internal const val BUS_MAX_SOURCE_AGE_MILLIS = 600_000L
internal const val BUS_MAX_RECEIPT_AGE_MILLIS = 60_000L
internal const val BUS_CLOCK_TOLERANCE_MILLIS = 60_000L

/** Receipt time is an explicit fallback when an official source timestamp was not supplied. */
internal fun officialBusEffectiveObservedAt(observedAt: Long?, fetchedAt: Long, now: Long): Long =
    minOf(observedAt ?: fetchedAt, fetchedAt, now)

/** Timestamp and ETA checks shared by the Seoul and Gyeonggi official adapters. */
internal fun officialBusArrivalTrust(seconds: Long?, observedAt: Long?, fetchedAt: Long,
    now: Long): ArrivalTrustState {
    if (seconds == null || seconds < 0L || seconds > 86_400L) return ArrivalTrustState.INVALID_ETA
    if (fetchedAt <= 0L || now < 0L) return ArrivalTrustState.UNKNOWN
    if (fetchedAt > now + BUS_CLOCK_TOLERANCE_MILLIS ||
        (observedAt != null && (observedAt > fetchedAt + BUS_CLOCK_TOLERANCE_MILLIS ||
            observedAt > now + BUS_CLOCK_TOLERANCE_MILLIS))) return ArrivalTrustState.FUTURE_TIMESTAMP
    val observed = officialBusEffectiveObservedAt(observedAt, fetchedAt, now)
    if (now - fetchedAt > BUS_MAX_RECEIPT_AGE_MILLIS ||
        now - observed > BUS_MAX_SOURCE_AGE_MILLIS || observed + seconds * 1_000L <= now)
        return ArrivalTrustState.STALE
    return ArrivalTrustState.VALID
}

internal fun officialBusArrivalExpiresAt(observedAt: Long?, fetchedAt: Long, now: Long): Long =
    minOf(officialBusEffectiveObservedAt(observedAt, fetchedAt, now) + BUS_MAX_SOURCE_AGE_MILLIS,
        minOf(fetchedAt, now) + BUS_MAX_RECEIPT_AGE_MILLIS)

/** Verified stop, route and boarding occurrence remain required when vehicle metadata is absent. */
internal fun chooseMatchingBusArrival(
    verifiedIdentity: OfficialBoardingIdentity?,
    arrivals: List<OfficialSeoulBusArrival>,
    nowEpochMillis: Long
): OfficialSeoulBusArrival? {
    if (verifiedIdentity == null || verifiedIdentity.authority != "seoul-bus" ||
        !verifiedIdentity.isComplete || nowEpochMillis < 0L) return null
    return arrivals.firstOrNull {
        it.identity == verifiedIdentity && it.snapshot.identity == verifiedIdentity &&
            it.isUsableBusArrival(nowEpochMillis)
    }
}

internal fun OfficialSeoulBusArrival.isUsableBusArrival(nowEpochMillis: Long): Boolean {
    val value = snapshot
    val received = value.receivedAtEpochMillis ?: gatewayFetchedAtEpochMillis ?: value.fetchedAtEpochMillis
    return identity.authority == "seoul-bus" && identity.isComplete && value.identity == identity &&
        isFull != true && !hasUncertainSeoulBusServiceState(officialArrivalMessage, issues) &&
        value.reliable && value.trustState == ArrivalTrustState.VALID &&
        value.validForMillis > 0L && value.fetchedAtEpochMillis > 0L && received > 0L &&
        nowEpochMillis - received in -BUS_CLOCK_TOLERANCE_MILLIS..BUS_MAX_RECEIPT_AGE_MILLIS &&
        nowEpochMillis - value.fetchedAtEpochMillis in
            -BUS_CLOCK_TOLERANCE_MILLIS..minOf(value.validForMillis, BUS_MAX_SOURCE_AGE_MILLIS) &&
        value.firstArrivalAtEpochMillis > nowEpochMillis &&
        (value.nextArrivalAtEpochMillis == null || value.nextArrivalAtEpochMillis > value.firstArrivalAtEpochMillis)
}

/** Actual vehicle IDs may have 9 or 10 digits; station and route IDs remain 9 digits. */
internal fun String.isOfficialBusVehicleId(): Boolean = matches(Regex("[1-9][0-9]{8,9}"))

/** Missing metadata cannot contradict a positive official ETA; explicit service errors can. */
internal fun hasUncertainSeoulBusServiceState(message: String, issues: Set<String>): Boolean =
    issues.any { it in setOf("SERVICE_ENDED", "BUS_SERVICE_ENDED", "STOP_MISMATCH", "ROUTE_MISMATCH",
        "DIRECTION_MISMATCH", "AUTHENTICATION_FAILED", "COMMUNICATION_FAILED") } ||
        Regex("운행\\s*종료").containsMatchIn(message)

internal fun seoulBusArrivalDisplayStatus(arrival: OfficialSeoulBusArrival, nowEpochMillis: Long): String {
    val value = arrival.snapshot
    val received = value.receivedAtEpochMillis ?: arrival.gatewayFetchedAtEpochMillis ?: value.fetchedAtEpochMillis
    val reason = when {
        arrival.isFull == true -> "만차 · 탑승 판단에서 제외"
        value.trustState == ArrivalTrustState.FUTURE_TIMESTAMP -> "원본·기기 시각 불일치 · 자동 판단 제외"
        value.trustState == ArrivalTrustState.INVALID_ETA -> "도착 예정시간 확인 필요"
        value.trustState == ArrivalTrustState.VALID && value.firstArrivalAtEpochMillis <= nowEpochMillis ->
            "예정시각 경과 · 다음 자동 갱신 대기"
        value.trustState == ArrivalTrustState.STALE ||
            nowEpochMillis - received > BUS_MAX_RECEIPT_AGE_MILLIS ||
            nowEpochMillis - value.fetchedAtEpochMillis > value.validForMillis -> "공식 도착정보 갱신 대기"
        arrival.isUsableBusArrival(nowEpochMillis) -> if (value.sourceTimestampAvailable)
            "실시간 자동 판단에 사용할 수 있어요" else "공식 도착정보 · 조회 수신 시각 기준"
        else -> arrival.statusMessage.ifBlank { "공식 도착정보 확인 필요" }
    }
    return listOf(arrival.officialArrivalMessage, reason,
        "막차".takeIf { arrival.isLast == true }).filterNotNull().filter(String::isNotBlank).joinToString(" · ")
}
