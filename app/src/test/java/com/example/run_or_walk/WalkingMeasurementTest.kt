package com.example.run_or_walk

import org.junit.Assert.*
import org.junit.Test

class WalkingMeasurementTest {
    private val now=1_800_000_000_000L
    private fun fix(meters: Double,at: Long,speed: Double=1.0,accuracy: Double=3.0) =
        GuidanceLocation(MapPoint(127.0,37.0+meters/111195.0),accuracy,at,speed,0.2)
    @Test fun observedStepsAndGpsProduceStrideSpeedAndMovingTime() {
        val session=WalkingMeasurementSession()
        session.start(StepSensorMode.COUNTER,1000)
        session.observeCounter(1000); session.observeLocation(fix(0.0,now),now)
        for(i in 1..30) {
            session.observeCounter(1000+i*5L)
            session.observeLocation(fix(i*4.0,now+i*4000),now+i*4000)
        }
        val result=session.result(121000)
        assertEquals(150,result.steps)
        assertEquals(120.0,result.distanceMeters,0.2)
        assertEquals(80.0,result.strideCentimeters!!,0.2)
        assertEquals(3.6,result.speedKmh!!,0.01)
        assertEquals(1000,result.secondsPerKm!!)
        assertTrue(result.reliableSpeed)
    }
    @Test fun stationaryGpsWobbleDoesNotGenerateDistanceOrSteps() {
        val session=WalkingMeasurementSession(); session.start(StepSensorMode.COUNTER,0)
        session.observeCounter(200)
        for(i in 0..30) session.observeLocation(fix(if(i%2==0) 0.0 else 3.0,now+i*4000,0.0),now+i*4000)
        val result=session.result(120000)
        assertEquals(0.0,result.distanceMeters,0.0); assertEquals(0,result.steps)
        assertNull(result.strideCentimeters); assertNull(result.speedKmh)
    }
    @Test fun vehicleMovementInaccurateOldFutureAndJumpFixesAreExcluded() {
        val session=WalkingMeasurementSession(); session.start(StepSensorMode.COUNTER,0)
        session.observeCounter(1); session.observeLocation(fix(0.0,now),now)
        session.observeCounter(6); session.observeLocation(fix(300.0,now+4000,25.0),now+4000)
        session.observeLocation(fix(304.0,now+8000,1.0,100.0),now+8000)
        session.observeLocation(fix(308.0,now-100000),now+12000)
        session.observeLocation(fix(312.0,now+30000),now+16000)
        assertEquals(0.0,session.result(16000).distanceMeters,0.0)
    }
    @Test fun unsupportedOrDeniedSensorNeverFabricatesStepsOrStride() {
        for(mode in listOf(StepSensorMode.UNAVAILABLE,StepSensorMode.PERMISSION_DENIED)) {
            val session=WalkingMeasurementSession(); session.start(mode,0)
            session.observeCounter(1000); session.observeStep()
            for(i in 0..30) session.observeLocation(fix(i*4.0,now+i*4000),now+i*4000)
            val result=session.result(120000)
            assertNull(result.steps); assertNull(result.strideCentimeters)
            assertTrue(result.reliableSpeed)
        }
    }
    @Test fun counterInitialReadingResetAndPausedPeriodDoNotBecomeSteps() {
        val session=WalkingMeasurementSession(); session.start(StepSensorMode.COUNTER,1000)
        session.observeCounter(50000); session.observeCounter(50005)
        session.observeCounter(3); session.observeCounter(8)
        session.pause(11000); session.observeCounter(500)
        session.start(StepSensorMode.COUNTER,31000)
        session.observeCounter(900); session.observeCounter(905)
        session.finish(41000)
        val result=session.result(51000)
        assertEquals(15,result.steps); assertTrue(result.sensorReset)
        assertEquals(20.0,result.activeSeconds,0.0)
        session.observeCounter(2000); session.observeStep()
        assertEquals(result,session.result(100000))
    }
    @Test fun detectorCountsOnlyActualEventsAndInsufficientMeasurementIsNotReliable() {
        val session=WalkingMeasurementSession(); session.start(StepSensorMode.DETECTOR,0)
        repeat(20) { session.observeStep() }
        assertEquals(20,session.result(20000).steps)
        assertFalse(session.result(20000).reliableSpeed)
        assertNull(session.result(20000).strideCentimeters)
    }
    @Test fun stopAndResumeDoesNotConnectGpsAcrossPause() {
        val session=WalkingMeasurementSession(); session.start(StepSensorMode.DETECTOR,0)
        session.observeLocation(fix(0.0,now),now); session.pause(4000)
        session.observeLocation(fix(100.0,now+8000),now+8000)
        session.start(StepSensorMode.DETECTOR,12000)
        repeat(5) { session.observeStep() }
        session.observeLocation(fix(200.0,now+12000),now+12000)
        assertEquals(0.0,session.result(12000).distanceMeters,0.0)
    }
}
