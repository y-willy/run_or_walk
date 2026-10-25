package com.example.run_or_walk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class ApiFailuresTest {
    @Test fun mapHttpFailuresToDistinctConfigurationQuotaAndServiceActions() {
        assertEquals(KakaoFailure.AUTHENTICATION, kakaoFailureForHttpStatus(401))
        assertEquals(KakaoFailure.AUTHENTICATION, kakaoFailureForHttpStatus(403))
        assertEquals(KakaoFailure.REQUEST_LIMIT, kakaoFailureForHttpStatus(429))
        assertEquals(KakaoFailure.SERVICE, kakaoFailureForHttpStatus(503))
    }

    @Test fun safeMessagesDoNotRetainRawProviderCauseOrLocation() {
        KakaoFailure.entries.forEach { failure ->
            val error = KakaoApiException(failure)
            assertEquals(kakaoFailureMessage(failure), friendlyKakaoFailure(error))
            assertNull(error.cause)
            assertFalse(error.message.orEmpty().contains("http"))
        }
        assertNull(friendlyKakaoFailure(IllegalStateException("raw response should not be shown")))
    }
}
