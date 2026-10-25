package com.example.run_or_walk

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

internal fun shouldSearchTransitAccess(error: Exception): Boolean =
    (error as? KakaoApiException)?.failure == KakaoFailure.NO_ROUTE

/** Search estimates lose their context when GPS moves, ages, or the app resumes much later. */
internal fun isAccessEstimateFresh(
    origin: MapPoint?, location: GuidanceLocation?, fetchedAtMillis: Long?, now: Long
): Boolean = origin != null && location != null && fetchedAtMillis != null &&
    now - fetchedAtMillis in 0L..120_000L && now - location.observedAtEpochMillis in 0L..30_000L &&
    location.accuracyMeters.isFinite() && location.accuracyMeters in 0.0..50.0 &&
    accessDistanceMeters(origin, location.point) <= 25.0

/**
 * An alternative public-transport trip from GPS to the selected departure is an estimate.
 * It is not proof that its feeder bus/train can be boarded, or that the original itinerary
 * can be caught. Only display a provisional margin when departure and first boarding coincide.
 */
internal fun provisionalAccessSlackSeconds(
    departure: MapPoint,
    boarding: MapPoint,
    estimatedAccessSeconds: Int,
    actualMainArrivalAtMillis: Long?,
    nowMillis: Long,
    bufferSeconds: Int
): Long? {
    if (estimatedAccessSeconds <= 0 || bufferSeconds < 0) return null
    val arrival = actualMainArrivalAtMillis ?: return null
    if (arrival <= nowMillis || arrival - nowMillis > 3_600_000L) return null
    if (accessDistanceMeters(departure, boarding) > 100.0) return null
    return (arrival - nowMillis) / 1_000 - estimatedAccessSeconds - bufferSeconds
}

private fun accessDistanceMeters(a: MapPoint, b: MapPoint): Double {
    if (!a.x.isFinite() || !a.y.isFinite() || !b.x.isFinite() || !b.y.isFinite()) return Double.POSITIVE_INFINITY
    val lat = Math.toRadians(b.y - a.y)
    val lon = Math.toRadians(b.x - a.x)
    val h = sin(lat / 2).let { it * it } + cos(Math.toRadians(a.y)) *
        cos(Math.toRadians(b.y)) * sin(lon / 2).let { it * it }
    return 6_371_000.0 * 2 * asin(sqrt(h.coerceIn(0.0, 1.0)))
}
