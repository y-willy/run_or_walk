package com.example.run_or_walk

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

/** Reception and ETA display are separate from proving a catchable, direction-matched vehicle. */
internal fun sourceSubwayEstimate(arrival: OfficialSubwayArrival, now: Long): String? =
    arrival.snapshot.takeIf { it.hasUsableOfficialEta(now) }?.let { officialEtaText(it, now) }

internal fun sourceBusEstimate(arrival: OfficialSeoulBusArrival, now: Long): String? =
    arrival.snapshot.takeIf { it.hasUsableOfficialEta(now) }?.let { officialEtaText(it, now) }

internal fun officialEtaText(snapshot: RealtimeArrivalSnapshot, now: Long): String {
    val seconds = ((snapshot.firstArrivalAtEpochMillis - now + 999L) / 1_000L).coerceAtLeast(0)
    return if (snapshot.etaPrecision == ArrivalTimePrecision.MINUTES) "약 ${(seconds + 59) / 60}분 후 도착"
        else "약 ${seconds / 60}분 ${seconds % 60}초 후 도착"
}

@Composable
internal fun OfficialArrivalStatusCard(screen: JourneyScreenState, now: Long) {
    val analysis = screen.timetableAnalysis?.takeIf { it.requestedRoute == screen.subwayDirectionRoute && it.stationStopId == screen.selectedStation?.stopId }
    val subwayRows = if (analysis?.directionVerified == true && analysis.matchingArrivals.isNotEmpty()) analysis.matchingArrivals
        else subwayDisplayArrivalRows(screen.subwayDirectionRoute, screen.arrivalOptions)
    val busRows = screen.busArrivalOptions.filter { it.identity == screen.selectedBusRoute?.identity }
    val match = screen.selectedGyeonggiMatch
    val gyeonggiRows = screen.gyeonggiArrivals.filter {
        match != null && it.matchesOfficialMatch(match)
    }
    if (subwayRows.isEmpty() && busRows.isEmpty() && gyeonggiRows.isEmpty()) return
    SectionCard("공식 도착정보 수신", if (screen.arrivalRequestFailed) "최근 갱신 실패 · 이전 수신 정보"
        else "예정시간·운행 상태와 이동 판단은 별도로 확인해요") {
        subwayRows.take(2).forEach { row ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(row.directionLabel, style = MaterialTheme.typography.labelLarge)
                val estimate = sourceSubwayEstimate(row, now)
                    ?: linkedSubwayEstimate(analysis,row,now)?.let { subwayEstimateText(it,now) }
                Text(estimate ?: row.officialArrivalMessage.ifBlank { "초 단위 예정시간 미제공" },
                    style = MaterialTheme.typography.bodyLarge)
                Text(if (estimate != null && !row.snapshot.hasUsableOfficialEta(now))
                    "예상 도착시간이에요." else subwayArrivalDisplayStatus(row, now),
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        analysis?.estimates?.firstOrNull {
            it.estimate.source == OfficialTimetableEstimateSource.OFFICIAL_TIMETABLE_SCHEDULE &&
                now <= it.estimate.validUntilEpochMillis
        }?.let { scheduled ->
            Text("다음 열차 · ${subwayEstimateText(scheduled.estimate, now)}",
                style = MaterialTheme.typography.bodyMedium)
        }
        busRows.take(2).forEach { row ->
            Text("${row.routeName} · ${row.directionLabel}", style = MaterialTheme.typography.labelLarge)
            sourceBusEstimate(row, now)?.let {
                Text(it, style = MaterialTheme.typography.bodyLarge)
            }
            Text(seoulBusArrivalDisplayStatus(row, now), style = MaterialTheme.typography.bodyMedium)
        }
        gyeonggiRows.take(2).forEach { row ->
            Text("${row.routeName} · ${row.destination}", style = MaterialTheme.typography.labelLarge)
            val usable = isUsableGyeonggiArrival(row, now)
            val remaining = row.seconds?.let { row.arrivalBasisMillis(now) + it * 1_000L - now }
            val text = if (usable && remaining != null) {
                if (row.etaPrecision == ArrivalTimePrecision.MINUTES) "약 ${(remaining + 59_999L) / 60_000L}분 후 도착"
                else "약 ${(remaining / 1_000L) / 60}분 ${(remaining / 1_000L) % 60}초 후 도착"
            } else "도착 예정시각 경과 또는 운행 상태 변경 · 다시 조회해요."
            Text(text, style = MaterialTheme.typography.bodyLarge)
            Text(if (usable) "경기도 공식 버스정보" + if (row.sourceTimestampAvailable) "" else " · 수신시각 기준"
                else "경기도 버스정보 갱신 중", style = MaterialTheme.typography.bodySmall)
        }
    }
}
