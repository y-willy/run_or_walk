package com.example.run_or_walk

import org.junit.Assert.*
import org.junit.Test

class DongdaemunAutonomousBusTest {
    private val point=MapPoint(127.04,37.58)
    private val boarding=JourneyPlace("kakao-hyundae","현대코아","",point,categoryName="버스정류장")
    // Synthetic IDs: exercise matching; these are not discovered operational station IDs.
    private val north=OfficialSeoulBusStop("123456781","06284","현대코아",point)
    private val south=OfficialSeoulBusStop("123456782","06285","현대코아",MapPoint(127.0401,37.58))
    private fun route(stop:OfficialSeoulBusStop,name:String="동대문A01",direction:String="경희의료원",order:String="12") =
        OfficialSeoulBusRoute(stop.stopId,"234567891",name,order,direction,"2",true)
    private val choice=KakaoTransitChoice(600,0,"동대문 A01","현대코아",point,firstTransitType="BUS",
        directionName="경희대의료원",alightingStopName="경희의료원")

    @Test fun autonomousVillageRoutesSearchSeoulFirstWithoutClassifyingByMOrRemovingA() {
        assertFalse(lookupGyeonggiFirst(null))
        assertFalse(lookupGyeonggiFirst(OfficialBoardingIdentity("seoul-bus","123456781","234567891","12","route")))
        assertTrue(lookupGyeonggiFirst(OfficialBoardingIdentity("GBIS","200000001","234000001","12","M5107")))
    }
    @Test fun a01NeverMatchesTheDifferent01Service() {
        assertNull(resolveOfficialSeoulBusRoute(listOf(route(north,"동대문01")),north.stopId,choice.serviceName))
        assertNotNull(resolveOfficialSeoulBusRoute(listOf(route(north)),north.stopId,choice.serviceName))
    }
    @Test fun hyundaeCoaSameNameOppositeStopsUseOfficialMedicalCenterDirection() {
        assertNull(resolveOfficialSeoulBusStop(listOf(north,south),boarding))
        val matched=resolveSeoulBusBoarding(listOf(north to listOf(route(north)),
            south to listOf(route(south,direction="장한평역",order="27"))),boarding,choice,
            mapOf("234567891" to listOf(
                OfficialSeoulBusRouteStop("234567891",north.stopId,"12","현대코아"),
                OfficialSeoulBusRouteStop("234567891","123456783","13","경희의료원"),
                OfficialSeoulBusRouteStop("234567891",south.stopId,"27","현대코아"),
                OfficialSeoulBusRouteStop("234567891","123456784","28","장한평역"))))!!
        assertEquals(north.stopId,matched.stop.stopId)
        assertEquals("12",matched.route.stopOrder)
    }
    @Test fun sameDirectionAmbiguityAndUnrelatedRoutesAreNotChosenByDistance() {
        assertNull(resolveSeoulBusBoarding(listOf(north to listOf(route(north)),
            south to listOf(route(south))),boarding,choice))
        assertNull(resolveSeoulBusBoarding(listOf(north to listOf(route(north,"201"))),boarding,choice))
    }
    @Test fun startingSeoulLookupRetiresGyeonggiFailureAndInFlightResponse() {
        val state=JourneyScreenState(FeatureMode.ROUTE)
        val old=state.beginGyeonggiRequest()
        state.gyeonggiMessage="경기 공식정보에서 하나로 연결하지 못했어요"
        state.beginSeoulBusLookup()
        assertFalse(state.acceptsGyeonggiResponse(old))
        assertNull(state.gyeonggiMessage)
        assertFalse(state.gyeonggiLoading)
    }
}
