package com.example.run_or_walk

import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SubwayPositionCandidateTest {
    private val date=LocalDate.of(2026,10,26)
    private fun at(t:String)=officialTimetableTimeAt(date,t)!!
    private val source=OfficialTimetableSource("서울교통공사","official","2026-09",at("00:00:00")-86400000,
        scheduleAsOfDate=LocalDate.of(2026,9,1))
    private val station=OfficialSubwayStation("mangpo","1075","망포","수인분당선")
    private val identity=OfficialBoardingIdentity("seoul","mangpo","1075","하행","수원행")
    private val route=KakaoTransitChoice(600,0,"수인분당선","망포",null,firstTransitType="SUBWAY",
        firstLegStopNames=listOf("망포","수원"),alightingStopName="수원")
    private val stops=listOf(
        OfficialTimetableStop(0,"sanggal","상갈",at("10:00:00"),at("10:00:30")),
        OfficialTimetableStop(1,"cheongmyeong","청명",at("10:03:00"),at("10:03:30")),
        OfficialTimetableStop(2,"yeongtong","영통",at("10:06:00"),at("10:06:30")),
        OfficialTimetableStop(3,"mangpo","망포",at("10:09:00"),at("10:09:30")),
        OfficialTimetableStop(4,"suwon","수원",at("10:12:00"),at("10:12:30")))
    private val trip=OfficialTimetableTrip("K6175",date,OfficialServiceDayType.WEEKDAY,"1075","하행","local",
        OfficialTrainOperationType.LOCAL,"mangpo-suwon",stops)
    private val now=at("10:02:00")
    private val observed=at("10:01:30")
    private fun row(number:String="",name:String?=null,message:String="[3]번째 전역",code:String?="99",trainType:String?="일반")=
        OfficialSubwayArrival(identity,"하행 · 수원행","일반",number,
            RealtimeArrivalSnapshot(identity,0,fetchedAtEpochMillis=observed,validForMillis=600000,reliable=false,trustState=ArrivalTrustState.UNKNOWN),
            gatewayFetchedAtEpochMillis=now,officialArrivalMessage=message,sourceReceivedAtEpochMillis=observed,
            gatewayContract=SubwayGatewayContract.RAW_FIELDS,trainType=trainType,destinationName="수원",reportedStationName=name,arrivalCode=code)
    private fun table(trips:List<OfficialTimetableTrip>)=OfficialTimetable(source,"seoul",
        listOf(OfficialTimetableCalendarDay(date,OfficialServiceDayType.WEEKDAY,"calendar")),trips)
    private fun analyzer(trips:List<OfficialTimetableTrip>)=SubwayTimetableAnalyzer(
        { _,number,_,_,_,decorated -> table(trips.filter { it.trainNo==number || decorated && it.trainNo.removePrefix("K").toIntOrNull()==number.toIntOrNull() }) },
        { _,_,_,_,_,_ -> table(trips) })
    private suspend fun analyze(row:OfficialSubwayArrival,trips:List<OfficialTimetableTrip> = listOf(trip))=
        analyzer(trips).analyze(station,route,listOf(row),now,date,OfficialServiceDayType.WEEKDAY,"calendar")

    @Test fun numberMissingAndOnlyThreeStationsAwayUsesRealStationIntervals()=runBlocking {
        val result=analyze(row())
        val e=result.estimates.single().estimate
        assertEquals(OfficialTimetableEstimateSource.OFFICIAL_TIMETABLE_POSITION,e.source)
        assertEquals(at("10:07:30"),e.earliestArrivalAtEpochMillis)
        assertEquals(at("10:10:30"),e.latestArrivalAtEpochMillis)
        assertEquals("sanggal",e.positionStopId)
    }
    @Test fun multipleTrainCandidatesStayARangeWithoutChoosingAnArbitraryTrain()=runBlocking {
        val second=trip.copy(trainNo="K6177",stops=stops.map { it.copy(
            arrivalAtEpochMillis=it.arrivalAtEpochMillis!!+120000,departureAtEpochMillis=it.departureAtEpochMillis!!+120000) })
        val e=analyze(row(),listOf(trip,second)).estimates.single().estimate
        assertEquals(2,e.candidateTrainNumbers.size)
        assertTrue(e.trainNo.startsWith("position-slot:"))
        assertTrue(e.label.contains("후보 2개"))
        assertTrue(e.earliestArrivalAtEpochMillis<e.latestArrivalAtEpochMillis)
    }
    @Test fun zeroPaddedLiveNumberCanResolveOnePublisherTrain()=runBlocking {
        assertEquals(OfficialTimetableEstimateSource.OFFICIAL_TIMETABLE_POSITION,
            analyze(row("006175",name="상갈")).estimates.single().estimate.source)
    }
    @Test fun expressStationCountDoesNotMistakePassingStationsForStops()=runBlocking {
        val e=analyze(row(trainType="급행"),listOf(trip.copy(operationType=OfficialTrainOperationType.EXPRESS,
            stops=stops.filter { it.stopId!="cheongmyeong" })))
        assertTrue(e.estimates.none { it.estimate.source==OfficialTimetableEstimateSource.OFFICIAL_TIMETABLE_POSITION })
    }
    @Test fun oppositeDirectionAndAlreadyPassedTrainAreExcluded()=runBlocking {
        val opposite=analyze(row().copy(identity=identity.copy(directionId="상행")),listOf(trip))
        assertTrue(opposite.matchingArrivals.isEmpty())
        val passed=analyze(row("6175",name="수원",message="수원 출발"))
        assertTrue(passed.estimates.none { it.estimate.trainNo==trip.trainNo })
    }
    @Test fun exactOfficialEtaWinsOverPositionAndTimetable()=runBlocking {
        val real=row("6175",name="상갈").copy(snapshot=RealtimeArrivalSnapshot(identity,now+200000,
            fetchedAtEpochMillis=now,validForMillis=600000,receivedAtEpochMillis=now))
        val result=analyze(real)
        assertEquals(now+200000,selectDestinationSubwayArrival(result,now)!!.snapshot.firstArrivalAtEpochMillis)
    }
    @Test fun positionEstimateFeedsWalkingAndImmediateNextTrainWithoutNextEta()=runBlocking {
        val result=analyze(row())
        val evidence=selectTimetableEvidence(result,now)!!
        val point=MapPoint(127.0,37.2)
        val input=AutoGuidanceInput(GuidanceLocation(point,10.0,now),GuidanceBoarding(identity,point,true),
            GuidanceWalkingRoute(point,point,identity,600,now,120000,900),null,true,timetableArrivals=evidence)
        val foreground=calculateAutoGuidance(input,now)
        val background=calculateAutoGuidance(input.copy(arrivalRequestFailed=true),now)
        assertEquals(AutoGuidanceStatus.NEXT_VEHICLE,foreground.status)
        assertEquals(NextVehicleState.ETA_PENDING,foreground.nextVehicleState)
        assertEquals(foreground.status,background.status)
        assertTrue(subwayEstimateText(evidence.first,now).matches(Regex("약 [0-9]+분 후")))
    }
    @Test fun missingArrivalCodeAndMinorClockLeadDoNotDiscardAUsefulPosition()=runBlocking {
        val result=analyze(row(code=null).copy(sourceReceivedAtEpochMillis=now+6000))
        assertEquals(OfficialTimetableEstimateSource.OFFICIAL_TIMETABLE_POSITION,result.estimates.single().estimate.source)
        assertEquals(now+6000,result.estimates.single().estimate.originalSourceTimestampEpochMillis)
    }
    @Test fun lastPositionExpiresWithoutBeingFreshenedByAnApiFailure()=runBlocking {
        val result=analyzer(listOf(trip)).analyze(station,route,listOf(row()),observed+300001,date,OfficialServiceDayType.WEEKDAY,"calendar")
        assertTrue(result.estimates.none { it.estimate.source==OfficialTimetableEstimateSource.OFFICIAL_TIMETABLE_POSITION })
    }
    @Test fun missingVehicleIdsAndZeroRowNumbersRetainSeparateOfficialResponseSlots() {
        val rows=org.json.JSONArray()
        for(n in listOf(3,4))rows.put(org.json.JSONObject().put("statnId",station.stopId).put("subwayId",station.routeId)
            .put("updnLine","하행").put("trainLineNm","수원행").put("rowNum",0).put("barvlDt",0)
            .put("recptnDt","2026-10-26 10:01:30").put("arvlMsg2","[$n]번째 전역"))
        val parsed=parseWorkerSubwayArrivals(org.json.JSONObject().put("fetchedAt",java.time.Instant.ofEpochMilli(now).toString())
            .put("arrivals",rows).toString(),station,now)
        assertEquals(setOf(1,2),parsed.map { it.officialSlot }.toSet())
    }
}
