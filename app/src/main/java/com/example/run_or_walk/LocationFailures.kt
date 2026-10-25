package com.example.run_or_walk

import java.io.IOException

enum class LocationFailure {
    PERMISSION_REQUIRED, SERVICES_DISABLED, TIMEOUT, UNAVAILABLE, INACCURATE, STALE, SERVICE_UNAVAILABLE
}

/** Contains no provider exception, coordinates, or account information. */
class DeviceLocationException(val failure: LocationFailure) : IOException(locationFailureMessage(failure))

fun locationFailureMessage(failure: LocationFailure): String = when (failure) {
    LocationFailure.PERMISSION_REQUIRED -> "위치 권한이 필요해요. 앱 권한 설정에서 위치를 허용해 주세요."
    LocationFailure.SERVICES_DISABLED -> "기기의 위치 서비스가 꺼져 있어요. 위치 설정을 켠 뒤 다시 확인해 주세요."
    LocationFailure.TIMEOUT -> "아직 현재 위치를 받지 못했어요. 창가나 실외에서 위치를 다시 확인해 주세요."
    LocationFailure.UNAVAILABLE -> "현재 위치 정보가 없어요. 위치 서비스를 확인하고 다시 시도해 주세요."
    LocationFailure.INACCURATE -> "위치 정확도가 낮아요. 앱 위치 권한의 정확한 위치를 켜고 창가나 실외에서 다시 확인해 주세요."
    LocationFailure.STALE -> "최근 위치를 받지 못했어요. 위치를 다시 확인해 주세요."
    LocationFailure.SERVICE_UNAVAILABLE -> "기기의 위치 제공 서비스에 연결하지 못했어요. Google Play 서비스와 위치 설정을 확인해 주세요."
}

fun friendlyLocationFailure(error: Exception): String? = when (error) {
    is DeviceLocationException -> locationFailureMessage(error.failure)
    is SecurityException -> locationFailureMessage(LocationFailure.PERMISSION_REQUIRED)
    else -> null
}

/** Same bounds used by automatic guidance; an old lastLocation never becomes a fresh fix. */
internal fun locationSampleFailure(sample: GuidanceLocation, nowEpochMillis: Long): LocationFailure? = when {
    !sample.point.x.isFinite() || !sample.point.y.isFinite() ||
        sample.point.x !in -180.0..180.0 || sample.point.y !in -90.0..90.0 -> LocationFailure.UNAVAILABLE
    sample.observedAtEpochMillis < 0L || sample.observedAtEpochMillis > nowEpochMillis ||
        nowEpochMillis - sample.observedAtEpochMillis > 30_000L -> LocationFailure.STALE
    !sample.accuracyMeters.isFinite() || sample.accuracyMeters < 0.0 ||
        sample.accuracyMeters > 50.0 -> LocationFailure.INACCURATE
    else -> null
}
