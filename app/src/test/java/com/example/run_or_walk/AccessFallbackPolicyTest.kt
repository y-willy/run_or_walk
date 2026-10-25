package com.example.run_or_walk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class AccessFallbackPolicyTest {
    private val same = MapPoint(127.0, 37.0)
    @Test fun authenticationNetworkAndSchemaFailuresDoNotMeanWalkingIsImpossible() {
        KakaoFailure.entries.forEach {
            assertEquals(it == KakaoFailure.NO_ROUTE, shouldSearchTransitAccess(KakaoApiException(it)))
        }
        assertFalse(shouldSearchTransitAccess(java.io.IOException()))
    }
    @Test fun movedOrExpiredGpsCannotKeepAnOldFeederMarginVisible() {
        val now = 1_790_000_000_000L
        val location = GuidanceLocation(same, 10.0, now)
        assertTrue(isAccessEstimateFresh(same, location, now, now))
        assertFalse(isAccessEstimateFresh(same, location.copy(point = MapPoint(127.01, 37.0)), now, now))
        assertFalse(isAccessEstimateFresh(same, location.copy(accuracyMeters = 80.0), now, now))
        assertFalse(isAccessEstimateFresh(same, location, now, now + 30_001L))
        assertFalse(isAccessEstimateFresh(same, location, now - 120_001L, now))
    }
    @Test fun estimatesMarginOnlyWhenOriginCoincidesWithBoardingAndRealtimeExists() {
        val now = 1_700_000_000_000L
        assertEquals(180L, provisionalAccessSlackSeconds(
            same, same, 300, now + 600_000L, now, 120))
        assertEquals(-120L, provisionalAccessSlackSeconds(
            same, same, 600, now + 600_000L, now, 120))
    }
    @Test fun neverInventsMarginWhenItineraryCannotBeConnected() {
        val now = 1_700_000_000_000L
        assertNull(provisionalAccessSlackSeconds(
            same, MapPoint(127.01, 37.01), 300, now + 600_000, now, 120))
        assertNull(provisionalAccessSlackSeconds(same, same, 300, null, now, 120))
        assertNull(provisionalAccessSlackSeconds(same, same, 300, now - 1, now, 120))
        assertNull(provisionalAccessSlackSeconds(same, same, 0, now + 600_000, now, 120))
    }
}
