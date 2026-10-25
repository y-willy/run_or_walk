package com.example.run_or_walk

import com.kakao.vectormap.MapAuthException
import com.kakao.vectormap.MapTimeoutException
import com.kakao.vectormap.RenderViewException
import java.io.IOException
import java.net.SocketTimeoutException

/** Safe to display or log: never includes an SDK message, URL, key, or coordinates. */
internal data class MapLoadFailure(
    val category: MapFailureCategory,
    val sdkCode: Int? = null
) {
    val diagnosticCode: String
        get() = "MAP_${category.name}" + (sdkCode?.let { ":$it" } ?: "")

    val guidance: String
        get() = when (category) {
            MapFailureCategory.CONFIGURATION -> "지도용 네이티브 앱 키 설정이 필요해요. 지도 인증 설정을 확인해 주세요."
            MapFailureCategory.AUTHENTICATION -> "카카오 지도 인증이 거절됐어요. 네이티브 앱 키와 Android 패키지·서명 키 해시 등록을 확인해 주세요."
            MapFailureCategory.NETWORK -> "네트워크 연결을 확인하고 다시 시도해 주세요."
            MapFailureCategory.TIMEOUT -> "지도 응답이 지연되고 있어요. 연결을 확인하고 다시 시도해 주세요."
            MapFailureCategory.SERVICE -> "지도 서비스 응답을 받지 못했어요. 잠시 후 다시 시도해 주세요."
            MapFailureCategory.NATIVE, MapFailureCategory.RENDERING ->
                "지도 화면을 시작하지 못했어요. 앱을 다시 열고 재시도해 주세요."
            MapFailureCategory.INITIALIZATION, MapFailureCategory.LIFECYCLE, MapFailureCategory.SDK ->
                "지도를 시작하지 못했어요. 다시 시도해 주세요."
        }

    companion object {
        fun from(error: Throwable): MapLoadFailure {
            // A bounded cause chain also handles malformed/cyclic exceptions safely.
            val causes = generateSequence(error) { it.cause }.take(8).toList()
            val auth = causes.filterIsInstance<MapAuthException>().firstOrNull()
            if (auth != null) return fromSdkCode(auth.errorCode)
            return MapLoadFailure(when {
                causes.any { it is MapTimeoutException || it is SocketTimeoutException } ->
                    MapFailureCategory.TIMEOUT
                causes.any { it is RenderViewException } -> MapFailureCategory.RENDERING
                causes.any { it is LinkageError } -> MapFailureCategory.NATIVE
                causes.any { it is IOException } -> MapFailureCategory.NETWORK
                else -> MapFailureCategory.SDK
            })
        }

        fun fromSdkCode(code: Int): MapLoadFailure = MapLoadFailure(
            category = when (code) {
                MapAuthException.APP_KEY_INVALID_ERROR, 401, 403 ->
                    MapFailureCategory.AUTHENTICATION
                MapAuthException.INITIALIZE_FAILURE -> MapFailureCategory.INITIALIZATION
                MapAuthException.RENDER_VIEW_FAILURE -> MapFailureCategory.RENDERING
                MapAuthException.SOCKET_TIMEOUT_EXCEPTION, MapAuthException.CONNECT_TIMEOUT_EXCEPTION ->
                    MapFailureCategory.TIMEOUT
                MapAuthException.CONNECT_INITIATE_FAILURE, MapAuthException.CONNECT_ERROR ->
                    MapFailureCategory.NETWORK
                429, in 500..599 -> MapFailureCategory.SERVICE
                499 -> MapFailureCategory.NETWORK
                else -> MapFailureCategory.SDK
            },
            sdkCode = code
        )
    }
}

internal enum class MapFailureCategory {
    CONFIGURATION, AUTHENTICATION, NETWORK, TIMEOUT, SERVICE, NATIVE,
    RENDERING, INITIALIZATION, LIFECYCLE, SDK
}
