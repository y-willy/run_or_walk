package com.example.run_or_walk

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable

internal data class MapArrivalRow(val arrivalAt:Long,val source:GuidanceArrivalSource,
    val precision:ArrivalTimePrecision,val receivedAt:Long)

/** Display-only source selection. It never feeds mixed-source or unverified rows into the calculator. */
internal fun mapArrivalRows(snapshot:RealtimeArrivalSnapshot?,evidence:TimetableArrivalEvidence?,now:Long):Pair<MapArrivalRow?,MapArrivalRow?> {
    val live=snapshot?.takeIf {it.hasUsableOfficialEta(now)}
    val identity=live?.identity ?: evidence?.identity
    fun estimate(value:OfficialTimetableEstimate?):MapArrivalRow? = value?.takeIf {
        val id=identity ?: return@takeIf false
        evidence?.identity==id && it.identity.stopId==id.stopId && it.identity.routeId==id.routeId && it.identity.directionId==id.directionId &&
            it.sourceTimestampEpochMillis<=it.receivedAtEpochMillis && it.receivedAtEpochMillis<=now && now<=it.validUntilEpochMillis &&
            it.earliestArrivalAtEpochMillis<=it.latestArrivalAtEpochMillis && it.midpointArrivalAtEpochMillis()>now
    }?.let {MapArrivalRow(it.midpointArrivalAtEpochMillis(),
        if(it.source==OfficialTimetableEstimateSource.OFFICIAL_TIMETABLE_SCHEDULE) GuidanceArrivalSource.OFFICIAL_TIMETABLE_SCHEDULE else GuidanceArrivalSource.OFFICIAL_TIMETABLE_POSITION,
        ArrivalTimePrecision.MINUTES,it.receivedAtEpochMillis)}
    val first=live?.let {MapArrivalRow(it.firstArrivalAtEpochMillis,GuidanceArrivalSource.OFFICIAL_REALTIME,it.etaPrecision,it.receivedAtEpochMillis ?: it.fetchedAtEpochMillis)}
        ?: estimate(evidence?.first)
    val next=live?.usableNextArrivalAt(now)?.let {MapArrivalRow(it,GuidanceArrivalSource.OFFICIAL_REALTIME,
        live.nextArrivalPrecision ?: live.etaPrecision,live.receivedAtEpochMillis ?: live.fetchedAtEpochMillis)}
        ?: evidence?.next?.takeIf {it.trainNo!=evidence.first.trainNo}?.let(::estimate)
    return first to next?.takeIf {first!=null && it.arrivalAt>first.arrivalAt}
}

@Composable
internal fun MapArrivalOverview(service:String,boarding:String,direction:String,snapshot:RealtimeArrivalSnapshot?,
    evidence:TimetableArrivalEvidence?,now:Long,missingText:String,error:String?) {
    val (first,next)=mapArrivalRows(snapshot,evidence,now)
    fun text(row:MapArrivalRow):String {
        val seconds=((row.arrivalAt-now)/1000).coerceAtLeast(0)
        val eta=if(row.precision==ArrivalTimePrecision.MINUTES) "약 ${((row.arrivalAt-now+30000)/60000).coerceAtLeast(1)}분 후"
            else "약 ${seconds/60}분 ${seconds%60}초 후 도착"
        return "$eta · ${arrivalSourceLabel(row.source)}"
    }
    SectionCard("공식 도착정보 수신",boarding) {
        Text(service);if(direction.isNotBlank()) Text(direction,style=MaterialTheme.typography.bodySmall)
        Text("첫 번째 차량 · "+(first?.let(::text) ?: missingText))
        Text("다음 차량 · "+(next?.let(::text) ?: "다음 차량 정보 확인 필요"))
        first?.receivedAt?.let {Text("첫 차량 수신 · ${((now-it)/1000).coerceAtLeast(0)}초 전",style=MaterialTheme.typography.bodySmall)}
        next?.receivedAt?.takeIf {it!=first?.receivedAt}?.let {Text("다음 차량 수신 · ${((now-it)/1000).coerceAtLeast(0)}초 전",style=MaterialTheme.typography.bodySmall)}
        error?.let {NoticeCard(it,isError=true)}
    }
}
