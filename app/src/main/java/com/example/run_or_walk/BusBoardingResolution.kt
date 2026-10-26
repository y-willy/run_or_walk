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
    return seoulBusStopCandidates(candidates, boarding).singleOrNull()
}

internal fun seoulBusStopCandidates(candidates: List<OfficialSeoulBusStop>, boarding: JourneyPlace): List<OfficialSeoulBusStop> {
    if (!boarding.isBusStop || !boarding.point.isBusCoordinate()) return emptyList()
    val expected = normalizeBusStopName(boarding.name)
    if (expected.isBlank()) return emptyList()
    return candidates.distinct().filter { candidate ->
        val point = candidate.point
        candidate.stopId.matches(officialBusId) && point != null && point.isBusCoordinate() &&
            normalizeBusStopName(candidate.name) == expected &&
            busStopDistanceMeters(boarding.point, point) <= 100.0
    }.distinctBy { it.stopId }
}

/** Lookup priority only; official stop/route/order must still be resolved from Seoul's API.
 * A is part of these published autonomous village route names, never an ignorable prefix.
 */
internal fun preferSeoulBusLookup(serviceName: String): Boolean = normalizeBusRouteName(serviceName) in
    setOf("동대문A01", "서대문A01", "동작A01")

internal data class SeoulBusBoardingMatch(val stop: OfficialSeoulBusStop, val route: OfficialSeoulBusRoute)

/** Resolve opposite-side same-name stops using actual route occurrences and direction. */
internal fun resolveSeoulBusBoarding(
    candidates: List<Pair<OfficialSeoulBusStop, List<OfficialSeoulBusRoute>>>,
    boarding: JourneyPlace, choice: KakaoTransitChoice
): SeoulBusBoardingMatch? {
    val physical = seoulBusStopCandidates(candidates.map { it.first }, boarding)
    val options = candidates.filter { it.first in physical }.flatMap { (stop, routes) ->
        routes.filter { it.stopId == stop.stopId && it.supported &&
            normalizeBusRouteName(it.name) == normalizeBusRouteName(choice.serviceName) &&
            it.routeId.matches(officialBusId) && it.stopOrder.toIntOrNull()?.let { n -> n > 0 } == true }
            .map { SeoulBusBoardingMatch(stop,it) }
    }.distinctBy { Triple(it.stop.stopId,it.route.routeId,it.route.stopOrder) }
    if (options.size == 1) return options.single()
    val direction = choice.directionName?.takeIf(String::isNotBlank) ?: choice.alightingStopName
        ?: return null
    return options.filter { normalizeBusDirection(it.route.direction) == normalizeBusDirection(direction) }.singleOrNull()
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
    val matching = candidates.distinct().filter {
        it.stopId == stopId && it.stopId.matches(officialBusId) && it.supported &&
            it.routeId.matches(officialBusId) &&
            it.stopOrder.toIntOrNull()?.let { order -> order > 0 } == true &&
            normalizeBusRouteName(it.name) == expected
    }.distinctBy { Triple(it.stopId, it.routeId, it.stopOrder) }
    if (matching.size == 1) return matching.single()
    if (directionName.isNullOrBlank()) return null
    return matching.filter { normalizeBusDirection(it.direction) == normalizeBusDirection(directionName) }.singleOrNull()
}

private fun normalizeBusDirection(value: String): String = normalizeBusRouteName(value)
    .replace("경희대의료원", "경희의료원")
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
