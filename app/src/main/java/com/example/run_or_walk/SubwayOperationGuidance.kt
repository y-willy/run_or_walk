package com.example.run_or_walk

/** Official messages are useful guidance even when the provider supplies no numeric ETA. */
internal data class SubwayOperationGuidance(
    val title: String,
    val message: String,
    val arrivalInformation: String,
    val sourceAtEpochMillis: Long,
    val hasCurrentObservation: Boolean
)

/**
 * Does not create a RealtimeArrivalSnapshot or infer travel time from station counts.
 * A late source observation remains useful as explicitly dated last-received information.
 * Station, line, optional confirmed service and route direction still constrain every row.
 */
internal fun subwayOperationGuidance(
    station: OfficialSubwayStation?,
    route: KakaoTransitChoice?,
    confirmedIdentity: OfficialBoardingIdentity?,
    arrivals: List<OfficialSubwayArrival>,
    now: Long,
    requestFailed: Boolean = false
): SubwayOperationGuidance? {
    if (station == null || now < 0 || requestFailed ||
        (route != null && route.firstTransitType != "SUBWAY")) return null
    if (confirmedIdentity != null && (confirmedIdentity.authority != "seoul" ||
        confirmedIdentity.stopId != station.stopId || confirmedIdentity.routeId != station.routeId)) return null
    val candidates = arrivals.filter { row ->
        val source = row.sourceReceivedAtEpochMillis
        val fetched = row.gatewayFetchedAtEpochMillis
        row.identity.authority == "seoul" && row.identity.stopId == station.stopId &&
            row.identity.routeId == station.routeId && row.snapshot.identity == row.identity &&
            (confirmedIdentity == null || row.identity == confirmedIdentity) &&
            row.identity.directionId in setOf("상행", "하행", "내선", "외선") &&
            row.gatewayContract == SubwayGatewayContract.RAW_FIELDS &&
            source != null && source >= 0 && fetched != null && source <= fetched && fetched <= now &&
            row.officialArrivalMessage.isNotBlank() && row.officialArrivalMessage.length <= 512 &&
            row.officialArrivalMessage.none(Char::isISOControl) &&
            row.issues.none { it in setOf(SubwayArrivalIssue.SOURCE_FUTURE, SubwayArrivalIssue.DEVICE_CLOCK_BEHIND) }
    }
    val routeDirectionPending = route != null && subwayRouteArrivalRows(route, candidates).isEmpty()
    val rows = subwayDisplayArrivalRows(route, candidates).distinctBy {
        Triple(it.identity, it.trainId, it.officialArrivalMessage)
    }.sortedByDescending { it.sourceReceivedAtEpochMillis }
        .groupBy { it.identity.directionId }.values.flatMap { it.take(2) }.take(4)
    if (rows.isEmpty()) return null
    val hasCurrent = rows.any { now - requireNotNull(it.sourceReceivedAtEpochMillis) <= 60_000L }
    val descriptions = rows.joinToString("\n") { row ->
        val age = (now - requireNotNull(row.sourceReceivedAtEpochMillis)) / 1_000L
        val previous = if (age > 60) "마지막 수신 위치 · " else ""
        "${row.directionLabel}\n$previous${row.officialArrivalMessage} · 원본 생성 ${age}초 전"
    }
    return SubwayOperationGuidance(
        if (routeDirectionPending) "열차 위치 수신 · 목적지 방향 대조 중"
        else if (hasCurrent) "열차 위치 안내 중" else "마지막 수신 열차 위치",
        descriptions + if (routeDirectionPending) "\n위치는 수신됐어요. 공식 정차 순서로 목적지 방면을 확인해요."
        else "\n도착시간 대신 공식 위치·운행 메시지로 안내해요.",
        if (rows.size == 1) rows.single().officialArrivalMessage else "방향별 위치 정보 수신",
        rows.maxOf { requireNotNull(it.sourceReceivedAtEpochMillis) }, hasCurrent)
}
