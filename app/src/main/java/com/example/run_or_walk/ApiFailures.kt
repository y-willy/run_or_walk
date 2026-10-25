package com.example.run_or_walk

import java.io.IOException

enum class KakaoFailure {
    SETUP_REQUIRED, AUTHENTICATION, REQUEST_LIMIT, NETWORK, TIMEOUT, NO_ROUTE, INVALID_RESPONSE, SERVICE
}

/** Only categorized, safe metadata reaches the UI; never retain a raw response/URL/cause. */
class KakaoApiException(val failure: KakaoFailure, val httpStatus: Int? = null) :
    IOException(kakaoFailureMessage(failure))

internal fun kakaoFailureForHttpStatus(status: Int): KakaoFailure = when (status) {
    401, 403 -> KakaoFailure.AUTHENTICATION
    429 -> KakaoFailure.REQUEST_LIMIT
    else -> KakaoFailure.SERVICE
}

fun kakaoFailureMessage(failure: KakaoFailure): String = when (failure) {
    KakaoFailure.SETUP_REQUIRED -> "카카오 REST API 연결 설정이 없는 앱이에요. 키가 설정된 앱으로 다시 빌드해 주세요."
    KakaoFailure.AUTHENTICATION -> "카카오 경로·장소 API 인증이 거절됐어요. REST API 키와 서비스 이용 권한을 확인해 주세요."
    KakaoFailure.REQUEST_LIMIT -> "카카오 경로·장소 API 요청이 제한됐어요. 잠시 후 다시 시도해 주세요."
    KakaoFailure.NETWORK -> "카카오 경로·장소 서버에 연결하지 못했어요. 인터넷 연결을 확인하고 다시 시도해 주세요."
    KakaoFailure.TIMEOUT -> "카카오 경로·장소 서버의 응답이 늦어지고 있어요. 잠시 후 다시 시도해 주세요."
    KakaoFailure.NO_ROUTE -> "선택한 두 지점 사이의 경로가 제공되지 않았어요. 승차 장소나 목적지를 확인해 주세요."
    KakaoFailure.INVALID_RESPONSE -> "경로·장소 응답의 형식을 확인할 수 없어요. 잠시 후 다시 조회해 주세요."
    KakaoFailure.SERVICE -> "카카오 경로·장소 서버에서 요청을 처리하지 못했어요. 잠시 후 다시 시도해 주세요."
}

fun friendlyKakaoFailure(error: Exception): String? =
    (error as? KakaoApiException)?.let { kakaoFailureMessage(it.failure) }
