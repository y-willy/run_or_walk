package com.example.run_or_walk

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

/** Reception and ETA display are separate from proving a catchable, direction-matched vehicle. */
internal fun sourceSubwayEstimate(arrival: OfficialSubwayArrival, now: Long): String? {
    val source = arrival.sourceReceivedAtEpochMillis ?: return null
    val seconds = arrival.sourceEtaSeconds?.takeIf { it in 1..86_400L } ?: return null
    val fetched = arrival.gatewayFetchedAtEpochMillis ?: return null
    if (source > fetched || fetched > now || now - source !in 0L..60_000L ||
        SubwayArrivalIssue.ARRIVAL_STATE_UNCERTAIN in arrival.issues ||
        arrival.gatewayContract != SubwayGatewayContract.RAW_FIELDS) return null
    val remaining = (source + seconds * 1_000L - now + 999L) / 1_000L
    if (remaining <= 0) return null
    return "원본 도착예정 약 ${remaining / 60}분 ${remaining % 60}초"
}

internal fun sourceBusEstimate(arrival: OfficialSeoulBusArrival, now: Long): String? {
    val source = arrival.snapshot.fetchedAtEpochMillis
    val fetched = arrival.gatewayFetchedAtEpochMillis ?: return null
    val remaining = (arrival.snapshot.firstArrivalAtEpochMillis - now + 999L) / 1_000L
    if (source <= 0 || source > fetched || fetched > now || now - source !in 0L..60_000L ||
        remaining !in 1..86_400L || arrival.issues.any {
            it in setOf("INVALID_ETA", "ETA_UNAVAILABLE", "VEHICLE_STATE_UNCERTAIN", "SERVICE_STATE_UNCERTAIN")
        }) return null
    return "원본 도착예정 약 ${remaining / 60}분 ${remaining % 60}초"
}

@Composable
internal fun OfficialArrivalStatusCard(screen: JourneyScreenState, now: Long) {
    val subwayRows = subwayRouteArrivalRows(screen.selectedRoute?.kakao, screen.arrivalOptions)
    val busRows = screen.busArrivalOptions.filter { it.identity == screen.selectedBusRoute?.identity }
    val match = screen.selectedGyeonggiMatch
    val gyeonggiRows = screen.gyeonggiArrivals.filter {
        match != null && it.stationId == match.stationId && it.routeId == match.routeId && it.staOrder == match.staOrder
    }
    if (subwayRows.isEmpty() && busRows.isEmpty() && gyeonggiRows.isEmpty()) return
    SectionCard("공식 도착정보 수신", if (screen.arrivalRequestFailed) "최근 갱신 실패 · 이전 수신 정보"
        else "예정시간·운행 상태와 이동 판단은 별도로 확인해요") {
        subwayRows.take(2).forEach { row ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(row.directionLabel, style = MaterialTheme.typography.labelLarge)
                val estimate = sourceSubwayEstimate(row, now).takeUnless { screen.arrivalRequestFailed }
                Text(estimate ?: row.officialArrivalMessage.ifBlank { "초 단위 예정시간 미제공" },
                    style = MaterialTheme.typography.bodyLarge)
                Text(subwayArrivalDisplayStatus(row, now), style = MaterialTheme.typography.bodySmall)
            }
        }
        busRows.take(2).forEach { row ->
            Text("${row.routeName} · ${row.directionLabel}", style = MaterialTheme.typography.labelLarge)
            sourceBusEstimate(row, now)?.takeUnless { screen.arrivalRequestFailed }?.let {
                Text(it, style = MaterialTheme.typography.bodyLarge)
            }
            Text(seoulBusArrivalDisplayStatus(row, now), style = MaterialTheme.typography.bodyMedium)
        }
        gyeonggiRows.take(2).forEach { row ->
            Text("${row.routeName} · ${row.destination}", style = MaterialTheme.typography.labelLarge)
            val source = row.observedAtMillis
            val remaining = source?.let { observed -> row.seconds?.let { observed + it * 1_000L - now } }
            val fresh = source != null && source <= row.fetchedAtMillis && row.fetchedAtMillis <= now &&
                now - source in 0L..60_000L && remaining != null && remaining > 0 && !screen.arrivalRequestFailed
            Text(if (fresh) "원본 도착예정 약 ${(requireNotNull(remaining) + 59_999L) / 60_000L}분"
                else "원본 예정시간·갱신 상태 확인 필요", style = MaterialTheme.typography.bodyLarge)
            Text(if (row.trust == ArrivalTrustState.VALID && fresh) "경기도 버스정보 제공"
                else "경기도 버스정보 수신 · 자동 이동 판단 보류", style = MaterialTheme.typography.bodySmall)
        }
    }
}
