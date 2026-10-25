package com.example.run_or_walk

import com.kakao.vectormap.MapAuthException
import com.kakao.vectormap.MapTimeoutException
import com.kakao.vectormap.RenderViewException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.IOException

class MapLoadFailureTest {
    @Test
    fun authenticationFailureKeepsOnlySafeNumericCode() {
        val failure = MapLoadFailure.from(
            MapAuthException(401, "request contained private key and location")
        )

        assertEquals(MapFailureCategory.AUTHENTICATION, failure.category)
        assertEquals("MAP_AUTHENTICATION:401", failure.diagnosticCode)
        assertFalse(failure.toString().contains("private"))
        assertFalse(failure.guidance.contains("private"))
    }

    @Test
    fun sdkNetworkAndRenderFailuresProvideDifferentRemedies() {
        assertEquals(MapFailureCategory.TIMEOUT,
            MapLoadFailure.fromSdkCode(MapAuthException.SOCKET_TIMEOUT_EXCEPTION).category)
        assertEquals(MapFailureCategory.NETWORK,
            MapLoadFailure.fromSdkCode(MapAuthException.CONNECT_ERROR).category)
        assertEquals(MapFailureCategory.NETWORK,
            MapLoadFailure.fromSdkCode(499).category)
        assertEquals(MapFailureCategory.AUTHENTICATION,
            MapLoadFailure.fromSdkCode(MapAuthException.APP_KEY_INVALID_ERROR).category)
        assertEquals(MapFailureCategory.RENDERING,
            MapLoadFailure.fromSdkCode(MapAuthException.RENDER_VIEW_FAILURE).category)
        assertEquals(MapFailureCategory.INITIALIZATION,
            MapLoadFailure.fromSdkCode(MapAuthException.INITIALIZE_FAILURE).category)
        assertEquals(MapFailureCategory.SERVICE, MapLoadFailure.fromSdkCode(429).category)
        assertEquals(MapFailureCategory.SERVICE, MapLoadFailure.fromSdkCode(503).category)
        assertEquals(MapFailureCategory.SDK, MapLoadFailure.fromSdkCode(777).category)
    }

    @Test
    fun wrappedNativeNetworkAndTimeoutFailuresRemainDiagnosable() {
        assertEquals(MapFailureCategory.NATIVE,
            MapLoadFailure.from(RuntimeException(UnsatisfiedLinkError("private path"))).category)
        assertEquals(MapFailureCategory.NETWORK,
            MapLoadFailure.from(RuntimeException(IOException("private URL"))).category)
        assertEquals(MapFailureCategory.TIMEOUT,
            MapLoadFailure.from(MapTimeoutException("private state")).category)
        assertEquals(MapFailureCategory.RENDERING,
            MapLoadFailure.from(RenderViewException("private state")).category)
    }

    @Test
    fun missingConfigurationHasActionableFailureWithoutSdkMessage() {
        val failure = MapLoadFailure(MapFailureCategory.CONFIGURATION)

        assertEquals("MAP_CONFIGURATION", failure.diagnosticCode)
        assertFalse(failure.guidance.isBlank())
    }

    @Test
    fun authenticationGuidanceNamesActualDeveloperConfiguration() {
        val failure = MapLoadFailure.fromSdkCode(401)
        org.junit.Assert.assertTrue(failure.guidance.contains("네이티브"))
        org.junit.Assert.assertTrue(failure.guidance.contains("서명 키 해시"))
        assertFalse(failure.guidance.contains("GPS"))
    }
}
