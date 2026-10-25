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

/** Only exact official stop + route + sequence + direction matches can authorize guidance. */
internal fun chooseMatchingBusArrival(
    verifiedIdentity: OfficialBoardingIdentity?,
    arrivals: List<OfficialSeoulBusArrival>,
    nowEpochMillis: Long
): OfficialSeoulBusArrival? {
    if (verifiedIdentity == null || verifiedIdentity.authority != "seoul-bus" ||
        !verifiedIdentity.isComplete || nowEpochMillis < 0L) return null
    val candidates = arrivals.filter {
        it.identity == verifiedIdentity && it.snapshot.identity == verifiedIdentity && it.vehicleId.isNotBlank()
    }.groupBy { it.vehicleId }.values.mapNotNull { it.distinct().singleOrNull() }
        .filter { it.isUsableBusArrival(nowEpochMillis) }
    val first = candidates.minOfOrNull { it.snapshot.firstArrivalAtEpochMillis } ?: return null
    return candidates.filter { it.snapshot.firstArrivalAtEpochMillis == first }.singleOrNull()
}

internal fun OfficialSeoulBusArrival.isUsableBusArrival(nowEpochMillis: Long): Boolean {
    val value = snapshot
    return identity.authority == "seoul-bus" && identity.isComplete && value.identity == identity &&
        vehicleId.isOfficialBusVehicleId() && isFull == false &&
        !hasUncertainSeoulBusServiceState(officialArrivalMessage, issues) &&
        value.reliable && value.trustState == ArrivalTrustState.VALID &&
        value.validForMillis > 0L && value.fetchedAtEpochMillis > 0L &&
        nowEpochMillis - value.fetchedAtEpochMillis in 0L..minOf(value.validForMillis, 60_000L) &&
        value.firstArrivalAtEpochMillis > nowEpochMillis &&
        (value.nextArrivalAtEpochMillis == null || value.nextArrivalAtEpochMillis > value.firstArrivalAtEpochMillis)
}

/** Actual vehicle IDs may have 9 or 10 digits; station and route IDs remain 9 digits. */
internal fun String.isOfficialBusVehicleId(): Boolean = matches(Regex("[1-9][0-9]{8,9}"))

/** Canonical issues describe uncertainty. A contradictory VALID flag must not erase them. */
internal fun hasUncertainSeoulBusServiceState(message: String, issues: Set<String>): Boolean =
    issues.isNotEmpty() || Regex("운행\\s*종료|출발\\s*대기|회차\\s*대기|정보\\s*없음").containsMatchIn(message)

internal fun seoulBusArrivalDisplayStatus(arrival: OfficialSeoulBusArrival, nowEpochMillis: Long): String {
    val reason = when {
        arrival.isFull == true -> "만차 · 탑승 판단에서 제외"
        arrival.snapshot.trustState == ArrivalTrustState.FUTURE_TIMESTAMP -> "원본·기기 시각 불일치 · 자동 판단 제외"
        arrival.snapshot.fetchedAtEpochMillis <= 0L -> arrival.statusMessage.ifBlank { "원본 생성 시각 미제공 · 자동 판단 제외" }
        arrival.snapshot.trustState == ArrivalTrustState.STALE ||
            nowEpochMillis - arrival.snapshot.fetchedAtEpochMillis > 60_000L -> "원본 도착정보 갱신 대기"
        arrival.snapshot.trustState == ArrivalTrustState.INVALID_ETA -> "도착 예정시간 확인 필요"
        arrival.isUsableBusArrival(nowEpochMillis) -> "실시간 자동 판단에 사용할 수 있어요"
        arrival.snapshot.trustState == ArrivalTrustState.VALID &&
            arrival.snapshot.firstArrivalAtEpochMillis <= nowEpochMillis -> "예정시각 경과 · 다음 자동 갱신 대기"
        else -> arrival.statusMessage.ifBlank { "차량·도착 상태 확인 필요" }
    }
    return listOf(arrival.officialArrivalMessage, reason,
        "막차".takeIf { arrival.isLast == true }).filterNotNull().filter(String::isNotBlank).joinToString(" · ")
}
