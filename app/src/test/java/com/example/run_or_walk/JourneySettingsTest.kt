package com.example.run_or_walk

import org.junit.Assert.*
import org.junit.Test

class JourneySettingsTest {
    @Test fun defaultsPreserveTheRealProviderDurationAndTwoMinuteBuffer() {
        val defaults = JourneySettings()
        assertTrue(defaults.useProviderWalkingTime)
        assertEquals(450, defaults.walkingSeconds(300, 450))
        assertEquals(120, defaults.bufferSeconds)
        assertEquals(6.0, defaults.fastSpeedKmh, 0.0)
    }

    @Test fun customPaceUsesActualWalkingDistanceAndRoundsUpToAWholeSecond() {
        val custom = JourneySettings(useProviderWalkingTime = false, speedKmh = 4.5)
        assertEquals(240, custom.walkingSeconds(300, 450))
        assertEquals(241, custom.walkingSeconds(301, 450))
        assertEquals(540, custom.copy(speedKmh = 2.0).walkingSeconds(300, 450))
        assertEquals(155, custom.copy(speedKmh = 7.0).walkingSeconds(300, 450))
        assertEquals(6.0, custom.fastSpeedKmh, 0.0)
        assertEquals(6.0, custom.copy(speedKmh = 6.0).fastSpeedKmh, 0.0)
        assertEquals(7.0, custom.copy(speedKmh = 7.0).fastSpeedKmh, 0.0)
    }

    @Test fun absentRouteDistanceDoesNotTurnAnExistingWalkIntoZeroSeconds() {
        val custom = JourneySettings(useProviderWalkingTime = false)
        assertEquals(450, custom.walkingSeconds(0, 450))
        assertEquals(0, custom.walkingSeconds(0, 0))
        assertEquals(Int.MAX_VALUE, custom.copy(speedKmh = 2.0).walkingSeconds(Int.MAX_VALUE, 1))
        assertThrows(IllegalArgumentException::class.java) { custom.walkingSeconds(-1, 1) }
        assertThrows(IllegalArgumentException::class.java) { custom.walkingSeconds(1, -1) }
    }

    @Test fun unsupportedSpeedAndSafetySettingsCannotEnterTheModel() {
        for (speed in listOf(Double.NaN, Double.POSITIVE_INFINITY, 1.5, 7.5, 4.3)) {
            assertThrows(IllegalArgumentException::class.java) { JourneySettings(speedKmh = speed) }
        }
        for (buffer in listOf(0, -30, 20, 615, 630)) {
            assertThrows(IllegalArgumentException::class.java) { JourneySettings(bufferSeconds = buffer) }
        }
        assertEquals(30, JourneySettings(bufferSeconds = 30).bufferSeconds)
        assertEquals(600, JourneySettings(bufferSeconds = 600).bufferSeconds)
    }

    @Test fun privateSettingsCodecRestoresExplicitCustomChoicesWithoutLiveData() {
        val chosen = JourneySettings(false, 3.5, 270)
        val payload = JourneySettingsCodec.encode(chosen)
        assertEquals(chosen, JourneySettingsCodec.decode(payload))
        for (sensitive in listOf("location", "arrival", "key", "token", "vehicle", "stopId")) {
            assertFalse(payload.contains(sensitive, ignoreCase = true))
        }
    }

    @Test fun malformedFutureAndOutOfRangePreferencesReturnSafeDefaults() {
        for (payload in listOf(null, "", "{", "[]", "x".repeat(4_097),
            """{"version":2,"useProviderWalkingTime":false,"speedKmh":3.0,"bufferSeconds":30}""")) {
            assertEquals(JourneySettings(), JourneySettingsCodec.decode(payload))
        }
        val partlyInvalid = JourneySettingsCodec.decode(
            """{"version":1,"useProviderWalkingTime":false,"speedKmh":1000,"bufferSeconds":-1}""")
        assertEquals(JourneySettings(false, 4.5, 120), partlyInvalid)
        val wrongTypes = JourneySettingsCodec.decode(
            """{"version":1,"useProviderWalkingTime":"false","speedKmh":"3.0","bufferSeconds":30.1}""")
        assertEquals(JourneySettings(), wrongTypes)
    }

    @Test fun changingPaceOrBufferChangesTheCatchDecisionWithoutChangingArrival() {
        val now = 1_790_000_000_000L
        val point = MapPoint(126.98, 37.57)
        val identity = OfficialBoardingIdentity("seoul-bus", "100000001", "100100001", "12", "100100001")
        val arrival = RealtimeArrivalSnapshot(identity, now + 400_000L, now + 1_000_000L,
            fetchedAtEpochMillis = now, validForMillis = 60_000L)
        fun calculate(settings: JourneySettings): AutoGuidanceAssessment = calculateAutoGuidance(
            AutoGuidanceInput(GuidanceLocation(point, 10.0, now), GuidanceBoarding(identity, point, true),
                GuidanceWalkingRoute(point, point, identity, settings.walkingSeconds(300, 450), now, 120_000L, 300),
                arrival, true, bufferSeconds = settings.bufferSeconds), now,
            AutoGuidancePolicy(fastSpeedKmh = settings.fastSpeedKmh))
        val fast = calculate(JourneySettings(false, 4.5, 120))
        val slow = calculate(JourneySettings(false, 2.0, 120))
        val extraBuffer = calculate(JourneySettings(false, 4.5, 300))
        assertEquals(AutoGuidanceStatus.WALK, fast.status)
        assertEquals(AutoGuidanceStatus.FAST_WALK, slow.status)
        assertEquals(AutoGuidanceStatus.NEXT_VEHICLE, extraBuffer.status)
        assertEquals(fast.firstArrivalAtEpochMillis, slow.firstArrivalAtEpochMillis)
        assertEquals(fast.firstArrivalAtEpochMillis, extraBuffer.firstArrivalAtEpochMillis)
    }

    @Test fun halfMinuteBufferLabelsStayAccurate() {
        assertEquals("30초", settingsBufferLabel(30))
        assertEquals("2분", settingsBufferLabel(120))
        assertEquals("2분 30초", settingsBufferLabel(150))
        assertEquals("10분", settingsBufferLabel(600))
    }
}
