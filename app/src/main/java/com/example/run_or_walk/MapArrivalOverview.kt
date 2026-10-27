package com.example.run_or_walk
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

@Composable
internal fun MapArrivalOverview(service:String,boarding:String,direction:String,snapshot:RealtimeArrivalSnapshot?,
    evidence:TimetableArrivalEvidence?,now:Long,missingText:String,error:String?) {
    val live=snapshot?.takeIf { it.hasUsableOfficialEta(now) }
    SectionCard("공식 도착정보 수신",boarding) {
        Text(service);if(direction.isNotBlank()) Text(direction,style=MaterialTheme.typography.bodySmall)
        Text("첫 번째 차량 · "+(live?.let { officialEtaText(it,now) } ?: evidence?.let { timetableArrivalText(it,now) } ?: missingText))
        val next=live?.usableNextArrivalAt(now)
        Text("다음 차량 · "+(next?.let { officialEtaText(requireNotNull(live).copy(firstArrivalAtEpochMillis=it,etaPrecision=live.nextArrivalPrecision ?: live.etaPrecision),now) }
            ?: evidence?.next?.let { subwayEstimateText(it,now) } ?: "다음 차량 정보 확인 필요"))
        val source=if(live!=null) GuidanceArrivalSource.OFFICIAL_REALTIME else if(evidence?.first?.source==OfficialTimetableEstimateSource.OFFICIAL_TIMETABLE_SCHEDULE)
            GuidanceArrivalSource.OFFICIAL_TIMETABLE_SCHEDULE else GuidanceArrivalSource.OFFICIAL_TIMETABLE_POSITION
        if(live!=null || evidence!=null) Text(arrivalSourceLabel(source),style=MaterialTheme.typography.bodySmall)
        (live?.receivedAtEpochMillis ?: evidence?.first?.receivedAtEpochMillis)?.let {
            Text("마지막 수신 · ${((now-it)/1000).coerceAtLeast(0)}초 전",style=MaterialTheme.typography.bodySmall)
        }
        error?.let {NoticeCard(it,isError=true)}
    }
}
