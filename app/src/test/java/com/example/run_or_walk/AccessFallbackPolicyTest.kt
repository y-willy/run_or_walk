package com.example.run_or_walk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AccessFallbackPolicyTest {
    private val same = MapPoint(127.0, 37.0)
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
