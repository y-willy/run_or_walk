package com.example.run_or_walk

import org.junit.Assert.*
import org.junit.Test

class PacePolicyContinuationTest {
    private val now=1_800_000_000_000L
    private val point=MapPoint(127.0,37.2)
    private val identity=OfficialBoardingIdentity("GBIS","200000001","234000001","12","M5107")
    private val match=GyeonggiOfficialMatch(identity.stopId,identity.routeId,"12","M5107","서울역",point)
    private fun fix(seconds:Long,speed:Double=1.3,accuracy:Double=5.0)=GuidanceLocation(
        point.copy(y=point.y+1.3*seconds/111195.0),accuracy,now+seconds*1000,speed,0.2)
    @Test fun sensorCalibrationWorksAcrossRealBackgroundPollingJitter() {
        val pace=WalkingPaceCalibrator()
        assertNull(pace.observe(fix(0),now))
        assertEquals(1.3,pace.observe(fix(31),now+31000)!!,0.001)
        assertEquals(1.3,pace.observe(fix(63),now+63000)!!,0.001)
        assertNull(pace.current(now+94001))
    }
    @Test fun inaccurateStationaryAndDuplicateSamplesCannotOverrideAValidWalkingSpeed() {
        val pace=WalkingPaceCalibrator()
        pace.observe(fix(0),now);pace.observe(fix(10),now+10000)
        assertEquals(1.3,pace.observe(fix(11,0.0,100.0),now+11000)!!,0.001)
        assertEquals(1.3,pace.observe(fix(10,2.3),now+12000)!!,0.001)
        assertNull(pace.observe(fix(13,0.0),now+13000))
    }
    @Test fun presetsAndCalibrationSettingsRoundTripWithoutReplacingProviderEta() {
        for((speed,seconds) in listOf(3.24 to 112,4.68 to 77,6.12 to 59)) {
            val settings=JourneySettings(false,speed,120,false)
            assertEquals(seconds,settings.walkingSeconds(100,90))
            assertEquals(settings,JourneySettingsCodec.decode(JourneySettingsCodec.encode(settings)))
        }
    }
    @Test fun settingsAndScreenReuseTheBackgroundCalibratedPaceAndErrorPolicy() {
        val settings=JourneySettings(false,4.68,120,true,measuredRunningSpeedKmh=6.0)
        val request=JourneyMonitoringRequest("승차 정류소",point,identity,settings=settings,gyeonggiMatch=match)
        val walk=GuidanceWalkingRoute(point,point,identity,400,now,120000,400)
        val eta=RealtimeArrivalSnapshot(identity,now+240000,fetchedAtEpochMillis=now,validForMillis=600000,receivedAtEpochMillis=now)
        val state=JourneyMonitoringState(true,request,location=fix(0),walkingRoute=walk,arrivals=eta,
            walkingDistanceMeters=400,providerWalkingSeconds=400,calibratedSpeedMetersPerSecond=1.0,arrivalRequestFailed=true)
        val actual=monitoringStateWithSettings(state,request,now,true)
        val expected=calculateMovementGuidance(AutoGuidanceInput(fix(0),GuidanceBoarding(identity,point,true),
            walk.copy(durationSeconds=settings.walkingSeconds(400,400,1.0)),eta,true,arrivalRequestFailed=true,bufferSeconds=120),now,
            settings)
        assertEquals(expected,actual.assessment)
        assertEquals(NextVehicleState.API_ERROR,actual.assessment!!.nextVehicleState)
    }
    @Test fun remoteGpsStopsWalkingGuidanceButDoesNotEraseAValidOfficialEta() {
        val request=JourneyMonitoringRequest("서울 승차점",point,identity,settings=JourneySettings(),gyeonggiMatch=match)
        val remote=GuidanceLocation(MapPoint(126.97,37.59),10.0,now)
        val eta=RealtimeArrivalSnapshot(identity,now+240000,fetchedAtEpochMillis=now,validForMillis=600000,receivedAtEpochMillis=now)
        val state=JourneyMonitoringState(true,request,location=remote,arrivals=eta)
        val actual=monitoringStateWithSettings(state,request,now,true)
        assertEquals(AutoGuidanceUnknownReason.WALKING_OUTSIDE_RANGE,actual.assessment!!.unknownReason)
        assertEquals(WALKING_OUTSIDE_RANGE_MESSAGE,actual.message)
        assertEquals(eta,actual.arrivals)
        assertTrue(actual.arrivals!!.hasUsableOfficialEta(now))
    }
    @Test fun routeBoardingSelectionUsesTheSpecifiedRoutePointRatherThanRemoteGps() {
        val choice=KakaoTransitChoice(3600,1,"동대문01","경희대의료원.경희여중고",MapPoint(127.052311,37.594127),firstTransitType="BUS")
        val boarding=routeBoardingPlace(JourneyTransitRoute("route",choice))!!
        assertTrue(boarding.isBusStop)
        val official=OfficialSeoulBusStop("105900050","06510",choice.boardingStopName,choice.boardingPoint)
        assertEquals(official,resolveOfficialSeoulBusStop(listOf(official),boarding))
        assertTrue(requiresTransitAccess(MapPoint(127.0,37.2),boarding.point))
    }
}
