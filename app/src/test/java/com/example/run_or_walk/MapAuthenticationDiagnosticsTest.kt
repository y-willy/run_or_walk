package com.example.run_or_walk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MapAuthenticationDiagnosticsTest {
    @Test
    fun signingHashUsesBase64Sha1RatherThanHexOrSha256() {
        assertEquals("qZk+NkcGgWq6PiVxeFDCbJzQ2J0=",
            kakaoSigningKeyHash("abc".toByteArray(Charsets.UTF_8)))
        assertNotEquals(kakaoSigningKeyHash("debug".toByteArray()),
            kakaoSigningKeyHash("release".toByteArray()))
    }

    @Test
    fun copyableIdentityKeepsEachActualSignerOnItsOwnLine() {
        val diagnostic = MapAuthenticationDiagnostics("example.package", listOf("first=", "second="))
        assertEquals("Android 패키지: example.package\nKakao 키 해시:\nfirst=\nsecond=", diagnostic.copyText)
    }

    @Test
    fun unreadableSignatureDoesNotInventAHash() {
        val diagnostic = MapAuthenticationDiagnostics("example.package", emptyList())
        assertTrue(diagnostic.copyText.contains("읽지 못했어요"))
        assertFalse(diagnostic.copyText.contains("null"))
    }
}
