package com.example.run_or_walk

import org.junit.Assert.*
import org.junit.Test

class GyeonggiApiFailuresTest {
    private val now = 1_790_000_000_000L
    private fun body(code: String, seconds: Int = 10) = """{"error":{"code":"$code","retryAfterSeconds":$seconds}}"""
    @Test fun distinguishesOriginal401403AndGeneric503WithoutAssumingMissingSecrets() {
        assertTrue(parseGyeonggiHttpFailure(502, body("GYEONGGI_KEY_REJECTED"), null, now).message!!.contains("401"))
        assertTrue(parseGyeonggiHttpFailure(502, body("GYEONGGI_ACCESS_FORBIDDEN"), null, now).message!!.contains("403"))
        val unavailable = parseGyeonggiHttpFailure(503, null, null, now)
        assertFalse(unavailable.message!!.contains("설정이 없"))
        assertNull(unavailable.serverCode)
        assertFalse(parseGyeonggiHttpFailure(502, body("GYEONGGI_GATEWAY_KEY_REJECTED"), null, now).message!!.contains("401"))
    }
    @Test fun realRetryAfterSurvivesOtherStationQueriesAndCannotBeShortenedByABody() {
        val failure = parseGyeonggiHttpFailure(429, body("GYEONGGI_DAILY_BUDGET", 60), "600", now)
        assertEquals(now + 600_000L, failure.retryAfterEpochMillis)
        val backoff = GyeonggiRequestBackoff()
        backoff.record(failure)
        assertSame(failure, runCatching { backoff.check(now + 30_000) }.exceptionOrNull())
        backoff.check(now + 600_000)
    }
    @Test fun unknownErrorTextAndMaliciousRetryValuesNeverReachUiOrLogs() {
        val failure = parseGyeonggiHttpFailure(502,
            """{"error":{"code":"https://example.test/?serviceKey=secret-value","retryAfterSeconds":9999999999}}""",
            "999999999999999999999", now)
        assertNull(failure.serverCode)
        assertNull(failure.retryAfterEpochMillis)
        assertFalse(failure.message!!.contains("secret-value"))
    }
}
