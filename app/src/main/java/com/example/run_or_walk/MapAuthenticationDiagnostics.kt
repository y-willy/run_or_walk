package com.example.run_or_walk

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest
import java.util.Base64

/** Public APK identity only. Never contains an API key, token, location or SDK response. */
internal data class MapAuthenticationDiagnostics(
    val packageName: String,
    val signingKeyHashes: List<String>
) {
    val copyText: String
        get() = "Android 패키지: $packageName\n" +
            if (signingKeyHashes.isEmpty()) "서명 키 해시를 읽지 못했어요."
            else "Kakao 키 해시:\n" + signingKeyHashes.joinToString("\n")
}

/** Use the installed APK, so cloud/Android Studio/release builds show their own actual signer. */
@Suppress("DEPRECATION")
internal fun readMapAuthenticationDiagnostics(context: Context): MapAuthenticationDiagnostics {
    val hashes = runCatching {
        val manager = context.packageManager
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            manager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                .signingInfo?.apkContentsSigners.orEmpty()
        } else {
            manager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
                .signatures.orEmpty()
        }
        signatures.map { kakaoSigningKeyHash(it.toByteArray()) }.distinct().sorted()
    }.getOrDefault(emptyList())
    return MapAuthenticationDiagnostics(context.packageName, hashes)
}

/** Kakao's Android identity format: Base64 of the signing certificate's SHA-1 digest. */
internal fun kakaoSigningKeyHash(certificate: ByteArray): String =
    Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest(certificate))
