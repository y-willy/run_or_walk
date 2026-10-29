package com.example.run_or_walk

import org.junit.Assert.*
import org.junit.Test

class SettingsBasedWalkingGuidanceTest {
    private val now = 1_800_000_000_000L
    private val origin = MapPoint(127.0, 37.0)
    private val end = MapPoint(127.0, 37.0027)
    private val id = OfficialBoardingIdentity("seoul", "1001000133", "1001", "상행", "서울역")
    private val settings = JourneySettings()
    private fun fix(speed: Double? = 0.0) = GuidanceLocation(origin, 5.0, now, speed, 0.2)

    private fun state(sample: GuidanceLocation, pace: Double? = 1.8): JourneyMonitoringState {
        val request = JourneyMonitoringRequest("서울역", end, id,
            subwayStation = OfficialSubwayStation(id.stopId, id.routeId, "서울역", "1호선"), settings = settings)
        return JourneyMonitoringState(true, request, location = sample,
            walkingRoute = GuidanceWalkingRoute(origin, end, id, 200, now, 120_000L, 300),
            arrivals = RealtimeArrivalSnapshot(id, now + 600_000L,
                fetchedAtEpochMillis = now, validForMillis = 60_000L, receivedAtEpochMillis = now),
            walkingDistanceMeters = 300, providerWalkingSeconds = 250,
            calibratedSpeedMetersPerSecond = pace)
    }

    @Test fun stoppedOrMissingSpeedUsesTheChosenPaceAndDoesNotBlockAdvice() {
        for (speed in listOf(0.0, null, Double.NaN, 0.2)) {
            val original = state(fix(speed))
            val result = monitoringStateWithSettings(original, original.request!!, now, true)
            assertEquals(231, result.walkingRoute!!.durationSeconds)
            assertEquals(AutoGuidanceStatus.RELAXED, result.assessment!!.status)
            assertEquals("여유 있어요", movementGuidanceTitle(result.assessment, now))
            assertNull(result.assessment.unknownReason)
            assertEquals(original.arrivals, result.arrivals)
        }
    }

    @Test fun measuredPaceCorrectsRatherThanReplacesTheConfiguredPace() {
        assertEquals(1.45, settings.walkingSpeedMetersPerSecond(1.8), 0.000001)
        assertEquals(207, settings.walkingSeconds(300, 250, 1.8))
        val slow = settings.copy(speedKmh = 3.24)
        assertEquals(1.08, slow.walkingSpeedMetersPerSecond(2.4), 0.000001)
        val fast = settings.copy(speedKmh = 6.12)
        assertEquals(1.385, fast.walkingSpeedMetersPerSecond(0.65), 0.000001)
    }

    @Test fun savedPersonalSpeedIsTheBaselineAndGpsCanBeDisabled() {
        val personal = settings.copy(personalWalkingSpeedKmh = 3.6, usePersonalWalkingSpeed = true)
        assertEquals(300, personal.walkingSeconds(300, 250, 0.0))
        assertEquals(1.15, personal.walkingSpeedMetersPerSecond(1.5), 0.000001)
        assertEquals(300, personal.copy(gpsAutoCalibrationEnabled = false).walkingSeconds(300, 250, 1.5))
        assertEquals(250, settings.copy(useProviderWalkingTime = true).walkingSeconds(300, 250, null))
    }

    @Test fun inaccurateMissingStaleAndVehicleVelocityFallBackWithoutInvalidatingGoodPosition() {
        val invalid = listOf(fix(1.8).copy(accuracyMeters = 30.0),
            fix(1.8).copy(speedAccuracyMetersPerSecond = 2.0),
            fix(1.8).copy(speedAccuracyMetersPerSecond = null),
            fix(1.8).copy(observedAtEpochMillis = now - 11_000L), fix(15.0), fix(null))
        for (sample in invalid) {
            assertNull(usableWalkingCalibration(1.8, sample, now))
            val original = state(sample)
            val result = monitoringStateWithSettings(original, original.request!!, now, true)
            assertEquals(231, result.walkingRoute!!.durationSeconds)
            assertEquals(AutoGuidanceStatus.RELAXED, result.assessment!!.status)
        }
    }

    @Test fun stoppedMovingAndGpsDegradationProduceTheSameForegroundAndBackgroundAssessment() {
        for (sample in listOf(fix(), fix(1.8), fix(1.8).copy(speedAccuracyMetersPerSecond = 3.0))) {
            val original = state(sample)
            val walk = original.walkingRoute!!.copy(durationSeconds = settings.walkingSeconds(300, 250,
                usableWalkingCalibration(1.8, sample, now)))
            val foreground = calculateMovementGuidance(AutoGuidanceInput(sample,
                GuidanceBoarding(id, end, true), walk, original.arrivals, true), now, settings)
            val background = monitoringStateWithSettings(original, original.request!!, now, true)
            assertEquals(foreground, background.assessment)
            assertNotEquals(AutoGuidanceStatus.UNKNOWN, foreground.status)
        }
    }

    @Test fun stoppedRouteDoesNotLoseDistanceAndARealWalkCollectsCalibration() {
        val calibrator = WalkingPaceCalibrator()
        val first = fix()
        assertNull(calibrator.observe(first, now))
        val tracker = WalkingRouteTracker(origin, KakaoWalkingRoute(300, 250, listOf(origin, end)), now)
        val original = state(first)
        val previous = LiveWalkingEvidence(first, original.walkingRoute, 300, 250, tracker.route.points)
        val stopped = updateLiveWalkingEvidence(previous, tracker, first, id, end, settings, null, now)
        assertEquals(300, stopped.distanceMeters)
        assertEquals(231, stopped.walking!!.durationSeconds)
        val moving = first.copy(speedMetersPerSecond = 1.8)
        calibrator.observe(moving.copy(observedAtEpochMillis = now + 1_000), now + 1_000)
        val next = moving.copy(point = origin.copy(y = origin.y + 18.0 / 111_195),
            observedAtEpochMillis = now + 11_000)
        assertEquals(1.8, calibrator.observe(next, now + 11_000)!!, 0.000001)
        assertNull(calibrator.observe(next.copy(speedMetersPerSecond = 0.0,
            observedAtEpochMillis = now + 12_000), now + 12_000))
    }

    @Test fun settingsSpeedDoesNotMakeAnExpiredPositionOrEtaUsable() {
        val original = state(fix().copy(observedAtEpochMillis = now - 31_000L))
        assertEquals(AutoGuidanceUnknownReason.LOCATION_STALE,
            monitoringStateWithSettings(original, original.request!!, now, true).assessment!!.unknownReason)
        val expired = state(fix()).let { it.copy(arrivals = it.arrivals!!.copy(firstArrivalAtEpochMillis = now - 1)) }
        assertEquals(AutoGuidanceStatus.UNKNOWN,
            monitoringStateWithSettings(expired, expired.request!!, now, true).assessment!!.status)
    }
}
