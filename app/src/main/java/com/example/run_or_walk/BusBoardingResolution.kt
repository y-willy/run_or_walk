package com.example.run_or_walk

import java.text.Normalizer
import java.util.Locale
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A Kakao place is only a search anchor. Never reinterpret its place ID or a displayed ARS number
 * as Seoul's nine-digit station ID, and never choose an opposite-side stop by nearest distance.
 */
internal fun resolveOfficialSeoulBusStop(
    candidates: List<OfficialSeoulBusStop>,
    boarding: JourneyPlace
): OfficialSeoulBusStop? {
    if (!boarding.isBusStop || !boarding.point.isBusCoordinate()) return null
    val expected = normalizeBusStopName(boarding.name)
    if (expected.isBlank()) return null
    return candidates.distinct().filter { candidate ->
        val point = candidate.point
        candidate.stopId.matches(officialBusId) && point != null && point.isBusCoordinate() &&
            normalizeBusStopName(candidate.name) == expected &&
            busStopDistanceMeters(boarding.point, point) <= 100.0
    }.singleOrNull()
}

/**
 * Route labels only resolve a selection within a previously confirmed official stop. Repeated
 * visits of a loop route have different stopOrder values and require an explicit choice.
 */
internal fun resolveOfficialSeoulBusRoute(
    candidates: List<OfficialSeoulBusRoute>,
    stopId: String,
    serviceName: String,
    directionName: String? = null
): OfficialSeoulBusRoute? {
    val expected = normalizeBusRouteName(serviceName)
    if (stopId.isBlank() || expected.isBlank()) return null
    return candidates.distinct().filter {
        it.stopId == stopId && it.stopId.matches(officialBusId) && it.supported &&
            it.routeId.matches(officialBusId) &&
            it.stopOrder.toIntOrNull()?.let { order -> order > 0 } == true &&
            (directionName.isNullOrBlank() || normalizeBusDirection(it.direction) == normalizeBusDirection(directionName)) &&
            normalizeBusRouteName(it.name) == expected
    }.singleOrNull()
}

private fun normalizeBusDirection(value: String): String = normalizeBusRouteName(value)
    .removeSuffix("방향").removeSuffix("방면").removeSuffix("행").removeSuffix("역")

private fun normalizeBusStopName(value: String): String =
    Normalizer.normalize(value, Normalizer.Form.NFC).trim()
        .removeSuffix("버스정류장").trim()
        .replace(Regex("\\s+"), "").replace('·', '.').replace('ㆍ', '.')

private fun normalizeBusRouteName(value: String): String =
    Normalizer.normalize(value, Normalizer.Form.NFC).trim()
        .replace(Regex("\\s+"), "").uppercase(Locale.ROOT)

private val officialBusId = Regex("[0-9]{9}")

private fun MapPoint.isBusCoordinate(): Boolean =
    x.isFinite() && y.isFinite() && x in -180.0..180.0 && y in -90.0..90.0

private fun busStopDistanceMeters(first: MapPoint, second: MapPoint): Double {
    val latitude = Math.toRadians(second.y - first.y)
    val longitude = Math.toRadians(second.x - first.x)
    val a = sin(latitude / 2).let { it * it } + cos(Math.toRadians(first.y)) *
        cos(Math.toRadians(second.y)) * sin(longitude / 2).let { it * it }
    return 6_371_000.0 * 2 * asin(sqrt(a.coerceIn(0.0, 1.0)))
}
