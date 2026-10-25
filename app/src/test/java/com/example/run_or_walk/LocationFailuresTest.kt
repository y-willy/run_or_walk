package com.example.run_or_walk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationFailuresTest {
    private val now = 1_000_000L
    private val sample = GuidanceLocation(MapPoint(127.0276, 37.4979), 12.0, now - 5_000L)

    @Test fun recentAccurateCacheRemainsUsableAtOriginalTimestamp() {
        assertNull(locationSampleFailure(sample, now))
        assertNull(locationSampleFailure(sample.copy(observedAtEpochMillis = now - 30_000L,
            accuracyMeters = 50.0), now))
        assertEquals(now - 5_000L, sample.observedAtEpochMillis)
    }

    @Test fun staleAndFutureCacheAreNeverAcceptedAsFreshGps() {
        assertEquals(LocationFailure.STALE, locationSampleFailure(sample.copy(
            observedAtEpochMillis = now - 30_001L), now))
        assertEquals(LocationFailure.STALE, locationSampleFailure(sample.copy(
            observedAtEpochMillis = now + 1L), now))
        assertEquals(LocationFailure.STALE, locationSampleFailure(sample.copy(
            observedAtEpochMillis = -1L), now))
    }

    @Test fun approximateOrUnknownAccuracyRequiresPreciseLocation() {
        for (accuracy in listOf(50.1, Double.POSITIVE_INFINITY, Double.NaN, -1.0)) {
            assertEquals(LocationFailure.INACCURATE,
                locationSampleFailure(sample.copy(accuracyMeters = accuracy), now))
        }
        assertTrue(locationFailureMessage(LocationFailure.INACCURATE).contains("정확한 위치"))
    }

    @Test fun invalidCoordinatesCannotProduceWalkingRoutes() {
        for (point in listOf(MapPoint(Double.NaN, 37.0), MapPoint(181.0, 37.0), MapPoint(127.0, 91.0))) {
            assertEquals(LocationFailure.UNAVAILABLE, locationSampleFailure(sample.copy(point = point), now))
        }
    }

    @Test fun locationFailuresHaveDistinctActionableMessages() {
        val failures = LocationFailure.entries.map { DeviceLocationException(it) }
        assertEquals(LocationFailure.entries.size, failures.map { it.message }.distinct().size)
        failures.forEach { assertNull(it.cause) }
        assertTrue(friendlyLocationFailure(SecurityException("private raw provider data"))!!.contains("위치 권한"))
        assertFalse(friendlyLocationFailure(SecurityException("private raw provider data"))!!.contains("private"))
        assertNull(friendlyLocationFailure(IllegalStateException("unclassified")))
    }
}
