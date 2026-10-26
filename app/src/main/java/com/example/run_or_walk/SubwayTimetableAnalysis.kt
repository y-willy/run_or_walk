package com.example.run_or_walk

import java.time.LocalDate

data class LinkedSubwayTimetableEstimate(
    val arrival: OfficialSubwayArrival,
    val estimate: OfficialTimetableEstimate
)

data class SubwayTimetableAnalysis(
    val matchingArrivals: List<OfficialSubwayArrival>,
    val estimates: List<LinkedSubwayTimetableEstimate>,
    val directionVerified: Boolean,
    val message: String?,
    val stationStopId: String = "",
    val routeId: String = "",
    val requestedRoute: KakaoTransitChoice? = null
)

typealias OfficialTimetableTrainLoader = suspend (
    routeId: String,
    trainNo: String,
    serviceDate: LocalDate,
    dayType: OfficialServiceDayType,
    calendarSourceVersion: String,
    includeDecoratedTrainNumbers: Boolean
) -> OfficialTimetable?

/**
 * Bridges official position rows to the packaged official timetable. The loader is injectable;
 * production uses OfficialTimetableRepository, tests do not need Android or a network.
 */
class SubwayTimetableAnalyzer(
    private val loader: OfficialTimetableTrainLoader,
    private val stationLoader: OfficialTimetableTrainLoader? = null
) {
    constructor(repository: OfficialTimetableRepository) : this(
        { routeId, trainNo, serviceDate, dayType, version, decorated ->
            repository.timetableForTrain(routeId, trainNo, serviceDate, dayType, version, decorated)
        },
        { routeId, stopId, serviceDate, dayType, version, _ ->
            repository.timetableForStation(routeId, stopId, serviceDate, dayType, version)
        }
    )

    suspend fun analyze(
        station: OfficialSubwayStation,
        route: KakaoTransitChoice?,
        arrivals: List<OfficialSubwayArrival>,
        nowEpochMillis: Long,
        serviceDate: LocalDate,
        dayType: OfficialServiceDayType,
        calendarSourceVersion: String,
        policy: OfficialTimetableEstimationPolicy = OfficialTimetableEstimationPolicy(),
        /** Previous service dates must also have explicit calendar evidence, especially at 24:xx. */
        additionalServiceDays: List<OfficialTimetableCalendarDay> = emptyList()
    ): SubwayTimetableAnalysis {
        fun result(matching: List<OfficialSubwayArrival> = emptyList(),
            estimates: List<LinkedSubwayTimetableEstimate> = emptyList(),
            verified: Boolean = false, message: String?) =
            SubwayTimetableAnalysis(matching, estimates, verified, message,
                station.stopId, station.routeId, route)
        if (route == null || route.firstTransitType != "SUBWAY") {
            return result(message = "목적지나 하차역을 선택하면 공식 시간표로 진행 방향을 확인해요.")
        }
        if (resolveOfficialBoardingStation(listOf(station), route.boardingStopName, route.serviceName) == null ||
            (route.officialBoardingStopId != null && route.officialBoardingStopId != station.stopId)) {
            return result(message = "선택한 경로의 탑승역·호선과 공식 역 정보가 일치하지 않아요.")
        }
        val waypointNames = route.firstLegStopNames.map(::timetableStationName).filter(String::isNotBlank)
        val boardingName = timetableStationName(station.name)
        val endpoint = route.alightingStopName?.let(::timetableStationName)?.takeIf(String::isNotBlank)
            ?: waypointNames.lastOrNull()?.takeIf { it != boardingName }
        if (endpoint == null || endpoint == boardingName || !validTimetableText(calendarSourceVersion)) {
            return result(message = "첫 지하철 구간의 하차역을 확인해야 진행 방향을 판단할 수 있어요.")
        }
        if (waypointNames.isNotEmpty() && (waypointNames.first() != boardingName ||
                waypointNames.last() != endpoint)) {
            return result(message = "경로의 첫 승차·하차역 정보가 서로 달라 진행 방향을 확인할 수 없어요.")
        }
        val calendarDays = (listOf(OfficialTimetableCalendarDay(serviceDate, dayType, calendarSourceVersion)) +
            additionalServiceDays).distinct()
        if (calendarDays.distinctBy { it.serviceDate }.size != calendarDays.size) {
            return result(message = "운행일의 공식 요일·휴일 정보가 서로 달라 시간표를 대조할 수 없어요.")
        }
        val proofs = mutableListOf<ProvenTimetableArrival>()
        val realtimeMatches = mutableListOf<OfficialSubwayArrival>()
        for (arrival in arrivals.distinctBy { Triple(it.identity, it.trainId, it.officialSlot) }) {
            if (arrival.identity.authority != "seoul" || arrival.identity.stopId != station.stopId ||
                arrival.identity.routeId != station.routeId || arrival.snapshot.identity != arrival.identity ||
                !arrival.identity.isComplete ||
                arrival.identity.directionId !in setOf("상행", "하행", "내선", "외선") ||
                arrival.gatewayContract != SubwayGatewayContract.RAW_FIELDS) continue
            val destination = arrival.destinationName ?: arrival.directionLabel.substringAfter(" · ", "")
                .takeIf { "행" in it }?.substringBefore("행")?.trim()
            val allCandidates = mutableListOf<ProvenTimetableArrival>()
            for (calendarDay in calendarDays) {
                if (!validTimetableText(arrival.trainId)) continue
                val exact = loader(station.routeId, arrival.trainId, calendarDay.serviceDate,
                    calendarDay.dayType, calendarDay.sourceVersion, false)
                val exactRows = exact?.trips.orEmpty().filter { it.trainNo == arrival.trainId }
                val table = if (exactRows.isNotEmpty()) exact else loader(station.routeId, arrival.trainId,
                    calendarDay.serviceDate, calendarDay.dayType, calendarDay.sourceVersion, true)
                if (table == null || !table.isStructurallyValid() || table.identityAuthority != "seoul") continue
                val candidates = table.trips.filter { trip ->
                    trip.routeId == station.routeId && trip.serviceDate == calendarDay.serviceDate &&
                        trip.dayType == calendarDay.dayType && trip.directionId == arrival.identity.directionId &&
                        (if (exactRows.isNotEmpty()) trip.trainNo == arrival.trainId
                        else decoratedTrainNumberMatches(arrival.trainId, trip.trainNo)) &&
                        provesBoardingPath(trip, station.stopId, endpoint, waypointNames) &&
                        declaredDestinationMatches(destination, trip) &&
                        trainOperationMatches(arrival.trainType, trip.operationType)
                }
                allCandidates += candidates.map { ProvenTimetableArrival(arrival, table, it, calendarDay) }
            }
            // A route/destination narrows a publisher-provided train record. It never turns a
            // repeated train number or unresolved branch into a choice by array order.
            if (allCandidates.size == 1) proofs += allCandidates.single()
            // A sound official ETA needs a proven destination direction, not a timetable
            // vehicle-ID match. Missing optional train numbers must not erase that ETA.
            if (allCandidates.size != 1 && arrival.snapshot.hasUsableOfficialEta(nowEpochMillis)) {
                val table = stationLoader?.invoke(station.routeId, station.stopId, serviceDate,
                    dayType, calendarSourceVersion, false)
                if (table != null && table.isStructurallyValid() && table.trips.any { trip ->
                        trip.directionId == arrival.identity.directionId &&
                            provesBoardingPath(trip, station.stopId, endpoint, waypointNames) &&
                            declaredDestinationMatches(destination, trip) &&
                            trainOperationMatches(arrival.trainType, trip.operationType)
                    }) realtimeMatches += arrival
            }
        }
        val matchingArrivals = (proofs.map { it.arrival } + realtimeMatches).distinct()
        if (matchingArrivals.isEmpty()) {
            return result(message = "공식 시간표에서 열차 번호·정차 경로·하차역을 함께 확인하지 못했어요.")
        }
        if (matchingArrivals.map { it.identity.directionId }.distinct().size != 1) {
            return result(message = "하차역에 도달하는 방향이 여러 개예요. 첫 구간의 경유역을 더 확인해 주세요.")
        }
        val estimates = proofs.mapNotNull { proof ->
            val arrival = proof.arrival
            // These codes describe the boarding station itself: do not promise a walking catch.
            if (arrival.arrivalCode !in setOf("3", "4", "5", "99")) return@mapNotNull null
            val positionName = arrival.reportedStationName?.takeIf(::validTimetableText)
                ?: reportedTimetableStationName(arrival.officialArrivalMessage)
                ?: return@mapNotNull null
            val matchingPositions = proof.trip.stops.filter {
                timetableStationName(it.stopName) == timetableStationName(positionName)
            }
            val position = matchingPositions.singleOrNull() ?: return@mapNotNull null
            val sourceAt = arrival.sourceReceivedAtEpochMillis ?: return@mapNotNull null
            val receivedAt = arrival.gatewayFetchedAtEpochMillis ?: return@mapNotNull null
            // The exact official train/route/path is now proved; attach the live service identity
            // without treating the publisher's GUBHANG/variant code as a live destination ID.
            val contextualTrip = proof.trip.copy(serviceVariant = arrival.identity.serviceVariant)
            val contextualTable = proof.table.copy(trips = listOf(contextualTrip))
            val observation = OfficialTrainPositionObservation("seoul", contextualTrip.trainNo,
                proof.day.serviceDate, proof.day.dayType, station.routeId, arrival.identity.directionId,
                arrival.identity.serviceVariant, position.stopId, position.stopName,
                OfficialTrainPositionKind.POSITION_REPORTED, sourceAt, receivedAt,
                contextualTrip.routePatternId)
            when (val estimated = estimateOfficialTimetableArrival(contextualTable, observation,
                arrival.identity, nowEpochMillis, policy)) {
                is OfficialTimetableEstimateResult.Estimated -> LinkedSubwayTimetableEstimate(arrival, estimated.estimate)
                is OfficialTimetableEstimateResult.Unknown -> null
            }
        }.sortedBy { it.estimate.earliestArrivalAtEpochMillis }
        val message = when {
            estimates.isNotEmpty() -> estimates.first().estimate.label
            proofs.any { it.arrival.arrivalCode == "2" } -> "해당 열차는 탑승역을 출발했어요. 다음 열차 정보를 기다려 주세요."
            proofs.any { it.arrival.arrivalCode in setOf("0", "1") } -> "열차가 탑승역에 진입·도착 중이에요. 걷기 시간으로 탑승을 판단하지 않아요."
            else -> "진행 방향은 확인했어요. 현재 열차 위치와 시간표를 대조할 수 있는 관측을 기다려요."
        }
        return result(matchingArrivals, estimates, true, message)
    }
}

private data class ProvenTimetableArrival(
    val arrival: OfficialSubwayArrival,
    val table: OfficialTimetable,
    val trip: OfficialTimetableTrip,
    val day: OfficialTimetableCalendarDay
)

/** A complete named-position message is accepted; the bracketed count is never used as time. */
internal fun reportedTimetableStationName(message: String): String? =
    Regex("^\\[[0-9]+]번째\\s*전역\\s*\\(([^()\\r\\n]+)\\)$").matchEntire(message.trim())
        ?.groupValues?.get(1)?.trim()?.takeIf(::validTimetableText)

private fun timetableStationName(value: String): String {
    val name = value.trim().replace(Regex("\\s+"), "")
        .replace(Regex("\\([^()]*\\)"), "").removeSuffix("역")
    return when (name) { "서울" -> "서울"; "이수" -> "총신대입구"; else -> name }
}

private fun provesBoardingPath(trip: OfficialTimetableTrip, boardingId: String,
    endpoint: String, waypointNames: List<String>): Boolean {
    val boarding = trip.stops.withIndex().filter { it.value.stopId == boardingId }.singleOrNull() ?: return false
    val destination = trip.stops.withIndex().filter {
        timetableStationName(it.value.stopName) == endpoint
    }.singleOrNull() ?: return false
    if (destination.index <= boarding.index) return false
    if (waypointNames.isEmpty()) return true
    var previous = boarding.index - 1
    for (name in waypointNames) {
        val matches = trip.stops.withIndex().filter {
            timetableStationName(it.value.stopName) == name && it.index in boarding.index..destination.index
        }
        val index = matches.singleOrNull()?.index ?: return false
        if (index <= previous) return false
        previous = index
    }
    return true
}

private fun declaredDestinationMatches(destination: String?, trip: OfficialTimetableTrip): Boolean =
    destination.isNullOrBlank() || timetableStationName(destination.removeSuffix("행")) ==
        timetableStationName(trip.stops.last().stopName)

private fun trainOperationMatches(trainType: String?, operation: OfficialTrainOperationType): Boolean = when (trainType) {
    "일반" -> operation !in setOf(OfficialTrainOperationType.EXPRESS, OfficialTrainOperationType.RAPID)
    "급행", "특급" -> operation != OfficialTrainOperationType.LOCAL
    else -> true
}

/** Explicit decoration grammar, followed by unique official path/destination matching above. */
private fun decoratedTrainNumberMatches(realtime: String, scheduled: String): Boolean {
    val pattern = Regex("^(?:[KS])?([0-9]+)$")
    val liveDigits = pattern.matchEntire(realtime)?.groupValues?.get(1) ?: return false
    val scheduleDigits = pattern.matchEntire(scheduled)?.groupValues?.get(1) ?: return false
    return liveDigits == scheduleDigits
}
