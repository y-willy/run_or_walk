package com.example.run_or_walk

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A physical place match and an official station match are separate from a train/direction match.
 * These helpers never assign a provider's stop ID to a Seoul stop or choose a train by list order.
 */
internal fun officialStationSearchQuery(placeName: String): String =
    stationNameParts(placeName).base

/** One exact station name and one official line must resolve to one catalog ID. */
internal fun resolveOfficialBoardingStation(
    candidates: List<OfficialSubwayStation>,
    boardingName: String,
    serviceName: String? = null
): OfficialSubwayStation? {
    val place = stationNameParts(boardingName)
    if (place.base.isBlank()) return null
    val lines = recognizedLines(boardingName) + recognizedLines(serviceName.orEmpty())
    if (lines.size > 1) return null
    val expectedLine = lines.singleOrNull()
    return candidates.asSequence()
        .filter { expectedLine == null || compact(it.lineName) == expectedLine }
        .filter { stationNamesMatch(place, stationNameParts(it.name)) }
        .distinctBy { it.stopId to it.routeId }
        .toList().singleOrNull()
}

/**
 * Resolve a route's estimated coordinate to a real category-verified place only when exactly one
 * nearby place matches the station name and any explicit line. Distance is a guard, never ranking.
 * An unknown category, conflicting line, missing coordinate or second plausible place needs a choice.
 */
internal fun resolveBoardingPlace(
    route: KakaoTransitChoice,
    candidates: List<JourneyPlace>
): JourneyPlace? {
    val origin = route.boardingPoint?.takeIf(MapPoint::isBoardingCoordinate) ?: return null
    val station = stationNameParts(route.boardingStopName)
    if (station.base.isBlank()) return null
    val lines = recognizedLines(route.boardingStopName) + recognizedLines(route.serviceName)
    if (lines.size > 1) return null
    val expectedLine = lines.singleOrNull()
    val matching = candidates.distinct().filter { candidate ->
        val categoryMatches = when (route.firstTransitType) {
            "SUBWAY" -> candidate.isSubwayStation
            "BUS" -> candidate.isBusStop
            else -> false
        }
        val candidateLines = recognizedLines(candidate.name)
        categoryMatches && candidate.point.isBoardingCoordinate() &&
            stationNamesMatch(station, stationNameParts(candidate.name)) &&
            candidateLines.size <= 1 &&
            (expectedLine == null || candidateLines.isEmpty() || candidateLines.single() == expectedLine) &&
            boardingDistanceMeters(origin, candidate.point) <= 300.0
    }
    // An explicitly matching line carries more evidence than a generic interchange name.
    val explicit = expectedLine?.let { line -> matching.filter { recognizedLines(it.name) == setOf(line) } }
        .orEmpty()
    return (explicit.ifEmpty { matching }).singleOrNull()
}

/**
 * Automatically bind an actual subway service only if Kakao explicitly names its
 * direction/destination and current trustworthy official rows agree on ONE full identity.
 * A station name, circle-line name or first returned train is never enough.
 */
internal fun resolveAutomaticSubwayIdentity(
    route: KakaoTransitChoice?,
    arrivals: List<OfficialSubwayArrival>,
    nowMillis: Long
): OfficialBoardingIdentity? {
    if (route?.firstTransitType != "SUBWAY") return null
    val supplied = directionHint(route.directionName.orEmpty())
    val nextStop = directionHint(route.nextStopName.orEmpty())
    // Missing both route hints never licenses the first API row as the correct platform.
    if (supplied.isBlank() && nextStop.isBlank()) return null
    val matches = subwayRouteArrivalRows(route, arrivals).filter { arrival ->
        val snapshot = arrival.snapshot
        if (!arrival.identity.isComplete ||
            !snapshot.reliable || snapshot.trustState != ArrivalTrustState.VALID ||
            nowMillis - snapshot.fetchedAtEpochMillis !in 0L..minOf(snapshot.validForMillis, 600_000L) ||
            snapshot.firstArrivalAtEpochMillis <= nowMillis) return@filter false
        true
    }
    return matches.map(OfficialSubwayArrival::identity).distinct().singleOrNull()
}

/** Display source observations even when ETA or optional train metadata cannot authorize guidance. */
internal fun subwayRouteArrivalRows(route: KakaoTransitChoice?, arrivals: List<OfficialSubwayArrival>): List<OfficialSubwayArrival> {
    if (route == null) return arrivals
    if (route.firstTransitType != "SUBWAY") return emptyList()
    val supplied = directionHint(route.directionName.orEmpty())
    val nextStop = directionHint(route.nextStopName.orEmpty())
    return arrivals.filter { arrival ->
        val officialService = arrival.directionLabel.substringAfter('·', "")
        val direction = directionHint(arrival.identity.directionId)
        val destination = directionHint(officialService.substringBefore('-'))
        val officialNext = directionHint(officialService.substringAfter('-', ""))
        val announced = directionHint(arrival.trainLabel.substringAfterLast('·', ""))
        val directionMatches = supplied.isBlank() || supplied in setOf(
            direction, destination, officialNext, announced, directionHint(officialService))
        // A second listed Kakao stop is useful only when it is the officially announced
        // next stop. A later alighting stop must not be mistaken for that next stop.
        val nextMatches = nextStop.isBlank() ||
            (officialNext.isNotBlank() && nextStop == officialNext)
        directionMatches && nextMatches
    }
}

/** Keep received source observations visible while the timetable resolves an ambiguous route hint. */
internal fun subwayDisplayArrivalRows(route: KakaoTransitChoice?, arrivals: List<OfficialSubwayArrival>): List<OfficialSubwayArrival> =
    subwayRouteArrivalRows(route, arrivals).ifEmpty { arrivals }

private data class StationNameParts(val full: String, val base: String)

private fun stationNameParts(value: String): StationNameParts {
    var name = compact(value)
    // Kakao station names often append a line after 역, e.g. 강남역 2호선.
    lineNames.firstOrNull { name.endsWith(it) && name.length > it.length }?.let {
        name = name.removeSuffix(it)
    }
    if (name.endsWith("역") && name.length > 1) name = name.dropLast(1)
    val full = name
    val base = name.replace(Regex("\\([^()]*\\)"), "").removeSuffix("역")
    return StationNameParts(full, base)
}

private fun stationNamesMatch(first: StationNameParts, second: StationNameParts): Boolean {
    if (first.full.isBlank() || second.full.isBlank()) return false
    if (first.full == second.full || first.base == second.base) return true
    // Official TOPIS uses 총신대입구(이수) on both lines; Kakao also uses 이수역.
    return setOf(first.base, second.base) == setOf("이수", "총신대입구")
}

private fun compact(value: String): String = value.trim().replace(Regex("\\s+"), "")
    .replace('·', ' ').replace(" ", "")

/** Normalize printed "역", "행" and "방면" suffixes without fuzzy place matching. */
private fun directionHint(value: String): String =
    compact(value).removeSuffix("방향").removeSuffix("방면")
        .removeSuffix("행").removeSuffix("역")

private fun recognizedLines(value: String): Set<String> {
    val text = compact(value)
    val matches = lineNames.filter { text.contains(it) }
    // 인천1호선 contains 1호선: keep only the more specific line name.
    return matches.filter { line -> matches.none { it != line && it.contains(line) } }.toSet()
}

private val lineNames = listOf(
    "경의중앙선", "수인분당선", "신분당선", "공항철도", "우이신설선", "김포골드라인",
    "인천1호선", "인천2호선", "신림선", "경춘선", "경강선", "서해선",
    "1호선", "2호선", "3호선", "4호선", "5호선", "6호선", "7호선", "8호선", "9호선"
).sortedByDescending(String::length)

private fun MapPoint.isBoardingCoordinate(): Boolean =
    x.isFinite() && y.isFinite() && x in -180.0..180.0 && y in -90.0..90.0

private fun boardingDistanceMeters(first: MapPoint, second: MapPoint): Double {
    val latitude = Math.toRadians(second.y - first.y)
    val longitude = Math.toRadians(second.x - first.x)
    val a = sin(latitude / 2).let { it * it } + cos(Math.toRadians(first.y)) *
        cos(Math.toRadians(second.y)) * sin(longitude / 2).let { it * it }
    return 6_371_000.0 * 2 * asin(sqrt(a.coerceIn(0.0, 1.0)))
}
