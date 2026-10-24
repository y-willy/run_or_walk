package com.example.run_or_walk

// Geographic coordinates: longitude (x), latitude (y).
data class MapPoint(val x: Double, val y: Double)

data class TransitStop(
    val id: Int,
    val name: String,
    val type: Int,
    val point: MapPoint,
    val line: String = ""
)

data class WalkRoute(val distanceMeters: Int, val durationSeconds: Int)

// Retained for saved state/test compatibility; there is no ODsay request implementation.
data class TransitPlan(
    val totalMinutes: Int,
    val boardingStop: TransitStop,
    val serviceName: String,
    val direction: String
)

data class KakaoPlace(
    val id: String,
    val name: String,
    val address: String,
    val point: MapPoint,
    val categoryGroupCode: String? = null,
    val categoryName: String? = null
) {
    // A station-like place name is not evidence: nearby shops also match searches.
    val isTransitStop: Boolean
        get() = categoryGroupCode == "SW8" || categoryName
            ?.split('>')
            ?.map(String::trim)
            ?.any { it in transitCategories } == true

    private companion object {
        val transitCategories = setOf("버스정류장", "지하철역", "전철역", "지하철역,전철역")
    }
}

data class KakaoWalkingRoute(
    val distanceMeters: Int,
    val durationSeconds: Int,
    val points: List<MapPoint>
)

enum class BoardingPointSource { VERIFIED, ESTIMATED, UNKNOWN }

data class KakaoTransitChoice(
    val totalSeconds: Int,
    val transfers: Int?,
    val serviceName: String,
    val boardingStopName: String,
    val boardingPoint: MapPoint?,
    val boardingSource: BoardingPointSource = BoardingPointSource.UNKNOWN,
    val firstTransitType: String = "",
    val totalWalkingSeconds: Int? = null,
    // Provider IDs must not be treated as Seoul official station/line IDs without
    // an independently verified mapping.
    val boardingStopId: String? = null,
    val officialBoardingStopId: String? = null,
    val transitLineId: String? = null,
    val directionName: String? = null
)

/** Unknown or incomplete WALK segment times never become a zero-minute walk. */
internal fun sumKnownWalkingSeconds(segmentSeconds: List<Int?>): Int? {
    if (segmentSeconds.isEmpty() || segmentSeconds.any { it == null || it < 0 }) return null
    val total = segmentSeconds.sumOf { requireNotNull(it).toLong() }
    return total.takeIf { it <= Int.MAX_VALUE }?.toInt()
}

/**
 * A compact, deterministic ordering when live boarding evidence is unavailable.
 * Supply feasibility only after checking official stop/service/direction identity,
 * GPS/walk quality and realtime freshness. Null means unknown; route duration
 * never stands in for a vehicle arrival time.
 */
fun selectTopTransitChoices(
    choices: List<KakaoTransitChoice>,
    boardingFeasibility: (KakaoTransitChoice) -> Boolean? = { null }
): List<KakaoTransitChoice> = choices
    .filter {
        it.totalSeconds > 0 && (it.transfers == null || it.transfers >= 0) &&
            (it.totalWalkingSeconds == null || it.totalWalkingSeconds >= 0)
    }
    .distinct()
    .map { choice ->
        choice to when (boardingFeasibility(choice)) {
            true -> 0
            null -> 1
            false -> 2
        }
    }
    .sortedWith(
        compareBy<Pair<KakaoTransitChoice, Int>> { it.second }
            .thenBy { it.first.totalSeconds }
            .thenBy { it.first.transfers ?: Int.MAX_VALUE }
            .thenBy { it.first.totalWalkingSeconds ?: Int.MAX_VALUE }
            .thenBy { it.first.firstTransitType }
            .thenBy { it.first.serviceName }
            .thenBy { it.first.boardingStopName }
            .thenBy { it.first.boardingStopId.orEmpty() }
            .thenBy { it.first.transitLineId.orEmpty() }
            .thenBy { it.first.directionName.orEmpty() }
            .thenBy { it.first.boardingPoint?.x ?: Double.POSITIVE_INFINITY }
            .thenBy { it.first.boardingPoint?.y ?: Double.POSITIVE_INFINITY }
            .thenBy { it.first.boardingSource.name }
            .thenBy { it.first.officialBoardingStopId.orEmpty() }
    )
    .take(3)
    .map { it.first }
