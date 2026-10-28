package com.example.run_or_walk

import java.io.File
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Replays supplied data at its original receipt time; no claim of live HTTP/device validation. */
class StationSubwayEtaRegressionTest {
    private val root=File("..")
    private val repo=OfficialTimetableRepository.fromAssets { File(root,"app/src/main/assets/$it").inputStream() }
    private val analyzer=SubwayTimetableAnalyzer(repo)
    private val now=Instant.parse("2026-10-25T13:55:58.715Z").toEpochMilli()
    private val day=OfficialTimetableCalendarDay(LocalDate.of(2026,10,25),OfficialServiceDayType.HOLIDAY,"government-2026")
    private val station=OfficialSubwayStation("1075075241","1075","망포","수인분당선")
    private val payload=File(root,"app/src/test/resources/mangpo_operation_response_20261025.json").readText()
    private fun rows()=parseWorkerSubwayArrivals(payload,station,now)
    private suspend fun analyze(rows:List<OfficialSubwayArrival>,identity:OfficialBoardingIdentity?=null)=
        analyzeSubwayWithScheduleFallback(analyzer,station,null,rows,now,listOf(day),identity)!!

    @Test fun actualMangpoPositionProducesNumericEstimatesWithoutAnyRouteOrDestination()=runBlocking {
        val result=analyze(rows())
        assertNull(result.requestedRoute)
        assertTrue(result.toString(),result.estimates.any { it.estimate.source==OfficialTimetableEstimateSource.OFFICIAL_TIMETABLE_POSITION })
        val info=stationSubwayArrivalInformation(result,now)!!
        println("Station-only supplied Mangpo: $info")
        assertTrue(info,info.contains("분"));assertFalse(info,info.contains("추정"))
        assertTrue(result.estimates.all { !it.estimate.isRealtime })
    }
    @Test fun aliasOnlyOriginalWorkerResponseAlsoUsesOfficialPositionAndTimetable()=runBlocking {
        val raw=JSONObject(payload)
        val aliases=JSONArray()
        val source=raw.getJSONArray("arrivals")
        for(i in 0 until source.length()) {
            val row=source.getJSONObject(i)
            aliases.put(JSONObject().apply { for(key in listOf("station","stationId","lineId","direction","destination","arrivalSeconds","arrivalMessage","receivedAt")) put(key,row.get(key)) })
        }
        val old=JSONObject().put("fetchedAt",raw.getString("fetchedAt")).put("arrivals",aliases)
        val parsed=parseWorkerSubwayArrivals(old.toString(),station,now)
        assertTrue(parsed.all { it.gatewayContract==SubwayGatewayContract.LEGACY_ALIASES })
        assertTrue(parsed.all { it.trainId.isBlank() })
        val result=analyze(parsed)
        assertTrue(result.toString(),result.estimates.any { it.estimate.source==OfficialTimetableEstimateSource.OFFICIAL_TIMETABLE_POSITION })
        assertTrue(stationSubwayArrivalInformation(result,now)!!.contains("분"))
    }
    @Test fun selectingAPositionOnlyDirectionAllowsEstimationAndGuidanceWithoutKakaoRoute()=runBlocking {
        val raw=rows();val chosen=raw.first { it.identity.directionId=="하행" }
        assertFalse(chosen.snapshot.reliable)
        val result=analyze(raw,chosen.identity)
        assertTrue(result.directionVerified)
        assertTrue(result.estimates.all { it.arrival.identity.directionId=="하행" })
        assertNotNull(linkedSubwayEstimate(result,chosen,now))
        val evidence=selectTimetableEvidence(result,now)!!
        val point=MapPoint(127.057,37.245)
        val input=AutoGuidanceInput(GuidanceLocation(point,10.0,now),GuidanceBoarding(evidence.identity,point,true),
            GuidanceWalkingRoute(point,point,evidence.identity,600,now,120000,900),null,true,timetableArrivals=evidence)
        val screen=calculateAutoGuidance(input,now)
        val background=calculateAutoGuidance(input.copy(arrivalRequestFailed=true),now)
        assertNotEquals(AutoGuidanceStatus.UNKNOWN,screen.status)
        assertEquals(screen.status,background.status)
        assertEquals(GuidanceArrivalSource.OFFICIAL_TIMETABLE_POSITION,screen.arrivalSource)
    }
    @Test fun multipleDirectionsGetNumericLabelsWithoutAnArbitraryAutomaticDirection()=runBlocking {
        val result=analyze(rows())
        val directions=result.estimates.map { it.arrival.identity.directionId }.distinct()
        assertTrue(directions.size>1)
        assertFalse(result.directionVerified)
        assertNull(selectTimetableEvidence(result,now))
        val info=stationSubwayArrivalInformation(result,now)!!
        assertTrue(info.contains("상행"));assertTrue(info.contains("하행"));assertTrue(info.contains("분"))
    }
    @Test fun officialEtaStillWinsWithNoItineraryOrTrainNumber()=runBlocking {
        val sample=JSONObject().put("fetchedAt",Instant.ofEpochMilli(now).toString()).put("arrivals",JSONArray().put(JSONObject()
            .put("stationId",station.stopId).put("lineId",station.routeId).put("direction","하행")
            .put("destination","고색행 - 매탄권선방면").put("arrivalSeconds","240").put("receivedAt","2026-10-25 22:55:58")))
        val parsed=parseWorkerSubwayArrivals(sample.toString(),station,now)
        val result=analyze(parsed,parsed.single().identity)
        val selected=selectDestinationSubwayArrival(result,now)!!
        assertEquals(240000L-715L,selected.snapshot.firstArrivalAtEpochMillis-now)
        assertTrue(stationSubwayArrivalInformation(result,now)!!.contains("분"))
    }
    @Test fun aDirectStationDestinationCanLinkThroughOfficialTimetableWithoutRouteApi()=runBlocking {
        val choice=repo.directStationChoice(station,"수원역",day)!!
        assertEquals("수인분당선",choice.serviceName)
        assertEquals(station.stopId,choice.officialBoardingStopId)
        assertEquals("수원",choice.alightingStopName)
        assertTrue(choice.totalSeconds>0)
        val result=analyzeSubwayWithScheduleFallback(analyzer,station,choice,rows(),now,listOf(day))!!
        assertTrue(result.directionVerified)
        assertNotNull(selectTimetableEvidence(result,now))
        assertTrue(result.estimates.all { it.arrival.identity.directionId=="하행" })
    }
    @Test fun selectedDirectionStillShowsScheduledArrivalWhenApiHasNoRows()=runBlocking {
        val weekday=OfficialTimetableCalendarDay(LocalDate.of(2026,10,26),OfficialServiceDayType.WEEKDAY,"government-2026")
        val time=officialTimetableTimeAt(weekday.serviceDate,"08:00:00")!!
        val selected=rows().first { it.identity.directionId=="하행" }.identity
        val result=analyzeSubwayWithScheduleFallback(analyzer,station,null,emptyList(),time,listOf(weekday),selected)!!
        val evidence=selectTimetableEvidence(result,time)!!
        assertEquals(OfficialTimetableEstimateSource.OFFICIAL_TIMETABLE_SCHEDULE,evidence.first.source)
        assertEquals("하행",evidence.identity.directionId)
        assertTrue(timetableArrivalText(evidence,time).contains("분"))
        val choices=selectableSubwayArrivals(emptyList(),result,time)
        assertTrue(choices.isNotEmpty())
        assertTrue(choices.all { linkedSubwayEstimate(result,it,time)!=null })
    }
    @Test fun lineOnePositionWithoutTrainNumberOrRouteAlsoReachesTimeAndGuidance()=runBlocking {
        val weekday=OfficialTimetableCalendarDay(LocalDate.of(2026,10,26),OfficialServiceDayType.WEEKDAY,"government-2026")
        val catalog=parseSeoulSubwayStations(File(root,"app/src/main/assets/seoul_subway_stations.json").readText())
        val seoul=catalog.single { it.routeId=="1001" && it.name=="서울" }
        val table=repo.timetableForStation(seoul.routeId,seoul.stopId,weekday.serviceDate,weekday.dayType,weekday.sourceVersion)!!
        val trip=table.trips.first { t -> val i=t.stops.indexOfFirst { it.stopId==seoul.stopId }; i>0 && i<t.stops.lastIndex && t.stops[i-1].arrivalAtEpochMillis!=null }
        val previous=trip.stops[trip.stops.indexOfFirst { it.stopId==seoul.stopId }-1]
        val time=previous.departureAtEpochMillis ?: previous.arrivalAtEpochMillis!!
        val id=OfficialBoardingIdentity("seoul",seoul.stopId,seoul.routeId,trip.directionId,"observed-service")
        val row=OfficialSubwayArrival(id,trip.directionId,"일반","",RealtimeArrivalSnapshot(id,0,fetchedAtEpochMillis=time,
            validForMillis=600000,reliable=false,trustState=ArrivalTrustState.UNKNOWN),gatewayFetchedAtEpochMillis=time,
            sourceReceivedAtEpochMillis=time,gatewayContract=SubwayGatewayContract.LEGACY_ALIASES,
            officialArrivalMessage="1번째 전역 (${previous.stopName})",reportedStationName=previous.stopName,
            destinationName=trip.stops.last().stopName,trainType="일반")
        val result=analyzeSubwayWithScheduleFallback(analyzer,seoul,null,listOf(row),time,listOf(weekday),id)!!
        assertNotNull(result.toString(),selectTimetableEvidence(result,time))
        assertTrue(stationSubwayArrivalInformation(result,time)!!.contains("분"))
    }
}
