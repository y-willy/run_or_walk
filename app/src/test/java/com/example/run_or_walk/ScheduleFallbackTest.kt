package com.example.run_or_walk

import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ScheduleFallbackTest {
    private val repository=OfficialTimetableRepository.fromAssets {
        File("src/main/assets/$it").inputStream()
    }
    @Test fun suinBundangWithoutRealtimeRowsUsesExplicitlyLabeledSchedule() = runBlocking {
        val date=LocalDate.of(2026,10,26);val now=officialTimetableTimeAt(date,"08:00:00")!!
        val station=OfficialSubwayStation("1075075241","1075","망포","수인분당선")
        val route=KakaoTransitChoice(600,0,"수인분당선","망포",null,firstTransitType="SUBWAY",
            firstLegStopNames=listOf("망포","수원"),alightingStopName="수원")
        val result=SubwayTimetableAnalyzer(repository).analyze(station,route,emptyList(),now,date,
            OfficialServiceDayType.WEEKDAY,"government-2026")
        assertTrue(result.toString(),result.directionVerified)
        assertTrue(result.matchingArrivals.isEmpty())
        assertTrue(result.estimates.isNotEmpty())
        assertTrue(result.estimates.all { it.estimate.source==OfficialTimetableEstimateSource.OFFICIAL_TIMETABLE_SCHEDULE && !it.arrival.snapshot.reliable })
        assertTrue(result.estimates.first().estimate.label.contains("실시간 위치 미확인"))
    }
    @Test fun lineOneScheduleAndMappedStationReachTheActualAlightingStop() = runBlocking {
        val date=LocalDate.of(2026,10,26);val now=officialTimetableTimeAt(date,"08:00:00")!!
        val stations=parseSeoulSubwayStations(File("src/main/assets/seoul_subway_stations.json").readText())
        val station=stations.single { it.routeId=="1001" && it.name=="서울" }
        val route=KakaoTransitChoice(600,0,"1호선","서울역",null,firstTransitType="SUBWAY",
            firstLegStopNames=listOf("서울역","용산"),alightingStopName="용산")
        val result=SubwayTimetableAnalyzer(repository).analyze(station,route,emptyList(),now,date,
            OfficialServiceDayType.WEEKDAY,"government-2026")
        assertTrue(result.toString(),result.directionVerified)
        assertTrue(result.estimates.isNotEmpty())
    }
    @Test fun lineOnePositionUsesTheSameTrainAndItsRealOfficialStopTimes() = runBlocking {
        val date=LocalDate.of(2026,10,26)
        val stations=parseSeoulSubwayStations(File("src/main/assets/seoul_subway_stations.json").readText())
        val station=stations.single { it.routeId=="1001" && it.name=="서울" }
        val table=repository.timetableForStation("1001",station.stopId,date,OfficialServiceDayType.WEEKDAY,"government-2026")!!
        val trip=table.trips.first { trip ->
            val i=trip.stops.indexOfFirst { it.stopId==station.stopId }
            i>0 && trip.stops.drop(i+1).any { it.stopName=="용산" } &&
                trip.stops[i-1].arrivalAtEpochMillis!=null && table.trips.count { it.trainNo==trip.trainNo }==1
        }
        val position=trip.stops[trip.stops.indexOfFirst { it.stopId==station.stopId }-1]
        val now=position.departureAtEpochMillis ?: position.arrivalAtEpochMillis!!
        val identity=OfficialBoardingIdentity("seoul",station.stopId,"1001",trip.directionId,"published-service")
        val row=OfficialSubwayArrival(identity,trip.directionId,trip.stops.last().stopName,trip.trainNo,
            RealtimeArrivalSnapshot(identity,0,fetchedAtEpochMillis=now,validForMillis=600000,reliable=false,trustState=ArrivalTrustState.UNKNOWN),
            gatewayFetchedAtEpochMillis=now,sourceReceivedAtEpochMillis=now,gatewayContract=SubwayGatewayContract.RAW_FIELDS,
            destinationName=trip.stops.last().stopName,reportedStationName=position.stopName,arrivalCode="99")
        val route=KakaoTransitChoice(600,0,"1호선","서울역",null,firstTransitType="SUBWAY",
            firstLegStopNames=listOf("서울역","용산"),alightingStopName="용산")
        val result=SubwayTimetableAnalyzer(repository).analyze(station,route,listOf(row),now,date,OfficialServiceDayType.WEEKDAY,"government-2026")
        assertTrue(result.toString(),result.estimates.any { it.estimate.source==OfficialTimetableEstimateSource.OFFICIAL_TIMETABLE_POSITION && it.estimate.trainNo==trip.trainNo })
    }
}
