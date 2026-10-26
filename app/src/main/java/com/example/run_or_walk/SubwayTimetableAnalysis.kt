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
        val calendarDays = (listOf(OfficialTimetableCalendarDay(serviceDate, dayType, calendarSourceVersion)) +
            additionalServiceDays).distinct()
        if (calendarDays.distinctBy { it.serviceDate }.size != calendarDays.size) {
            return result(message = "운행일의 공식 요일·휴일 정보가 서로 달라 시간표를 대조할 수 없어요.")
        }
        suspend fun scheduleFallback(): List<LinkedSubwayTimetableEstimate> {
            val exactPath = mutableListOf<LinkedSubwayTimetableEstimate>()
            val endpointPath = mutableListOf<LinkedSubwayTimetableEstimate>()
            for (day in calendarDays) {
                val table = stationLoader?.invoke(station.routeId, station.stopId, day.serviceDate,
                    day.dayType, day.sourceVersion, false) ?: continue
                if (!table.isStructurallyValid() || table.source.publishedAtEpochMillis > nowEpochMillis) continue
                for (trip in table.trips) {
                    // The published boarding-to-alighting call sequence establishes direction.
                    // Kakao's displayed intermediate names are useful when they match, but a
                    // missing/abbreviated waypoint must not hide a published departure time.
                    if (!provesBoardingPath(trip, station.stopId, endpoint, emptyList())) continue
                    if (arrivals.any { arrival -> arrival.trainId.isNotBlank() &&
                            decoratedTrainNumberMatches(arrival.trainId,trip.trainNo) &&
                            arrival.sourceReceivedAtEpochMillis?.let { nowEpochMillis-it in -60_000L..300_000L } == true &&
                            (arrival.arrivalCode in setOf("0","1","2") || positionBeyondBoarding(arrival,trip,station.stopId)) }) continue
                    val stop = trip.stops.singleOrNull { it.stopId == station.stopId } ?: continue
                    val at = stop.arrivalAtEpochMillis ?: stop.departureAtEpochMillis ?: continue
                    if (at <= nowEpochMillis || at - nowEpochMillis > 3_600_000L) continue
                    val identity = OfficialBoardingIdentity("seoul", station.stopId, station.routeId,
                        trip.directionId, "schedule:${trip.routePatternId}")
                    val estimate = OfficialTimetableEstimate(identity, trip.trainNo, trip.serviceDate,
                        trip.dayType, trip.operationType, trip.routePatternId, "", stop.sequence,
                        at, at, at, 0, 0, table.source.publishedAtEpochMillis, nowEpochMillis, 0,
                        minOf(nowEpochMillis + 60_000L, at), table.source, day.sourceVersion,
                        OfficialTimetableEstimateSource.OFFICIAL_TIMETABLE_SCHEDULE)
                    // Explicit schedule record: never a reliable realtime snapshot or observed train.
                    val row = OfficialSubwayArrival(identity, trip.directionId, "시간표 운행 ${trip.trainNo}",
                        trip.trainNo, RealtimeArrivalSnapshot(identity, 0, fetchedAtEpochMillis = 0,
                            validForMillis = 0, reliable = false, trustState = ArrivalTrustState.UNKNOWN),
                        statusMessage = estimate.label)
                    val candidate = LinkedSubwayTimetableEstimate(row, estimate)
                    endpointPath += candidate
                    if (provesBoardingPath(trip, station.stopId, endpoint, waypointNames)) exactPath += candidate
                }
            }
            // Prefer the complete provider path. If it cannot be reconciled with the
            // published stop names, use the verified endpoint path only when ONE
            // official travel direction remains. This is a timetable, not a train match.
            val selected = exactPath.ifEmpty { endpointPath }
            val sorted = selected.distinctBy { Triple(it.estimate.serviceDate, it.estimate.trainNo, it.estimate.routePatternId) }
                .sortedBy { it.estimate.earliestArrivalAtEpochMillis }
            // A branch/loop must be resolved by route waypoints, not by whichever train is first.
            return sorted.takeIf { it.map { row -> row.estimate.identity.directionId }.distinct().size == 1 }
                ?.take(6).orEmpty()
        }
        val proofs = mutableListOf<ProvenTimetableArrival>()
        val realtimeMatches = mutableListOf<OfficialSubwayArrival>()
        for (arrival in arrivals.distinctBy { Triple(it.identity, it.trainId, it.officialSlot) }) {
            if (arrival.identity.authority != "seoul" || arrival.identity.stopId != station.stopId ||
                arrival.identity.routeId != station.routeId || arrival.snapshot.identity != arrival.identity ||
                !arrival.identity.isComplete ||
                arrival.identity.directionId !in setOf("상행", "하행", "내선", "외선") ||
                arrival.gatewayContract == SubwayGatewayContract.UNKNOWN) continue
            val destination = arrival.destinationName ?: arrival.directionLabel.substringAfter(" · ", "")
                .takeIf { "행" in it }?.substringBefore("행")?.trim()
            val allCandidates = mutableListOf<ProvenTimetableArrival>()
            for (calendarDay in calendarDays) {
                val hasTrainNumber = validTimetableText(arrival.trainId)
                val exact = if (hasTrainNumber) loader(station.routeId, arrival.trainId, calendarDay.serviceDate,
                    calendarDay.dayType, calendarDay.sourceVersion, false)
                    else stationLoader?.invoke(station.routeId, station.stopId, calendarDay.serviceDate,
                        calendarDay.dayType, calendarDay.sourceVersion, false)
                val exactRows = exact?.trips.orEmpty().filter { it.trainNo == arrival.trainId }
                val table = if (!hasTrainNumber || exactRows.isNotEmpty()) exact else loader(station.routeId, arrival.trainId,
                    calendarDay.serviceDate, calendarDay.dayType, calendarDay.sourceVersion, true)
                if (table == null || !table.isStructurallyValid() || table.identityAuthority != "seoul") continue
                val candidates = table.trips.filter { trip ->
                    trip.routeId == station.routeId && trip.serviceDate == calendarDay.serviceDate &&
                        trip.dayType == calendarDay.dayType && trip.directionId == arrival.identity.directionId &&
                        (!hasTrainNumber || (if (exactRows.isNotEmpty()) trip.trainNo == arrival.trainId
                        else decoratedTrainNumberMatches(arrival.trainId, trip.trainNo))) &&
                        provesBoardingPath(trip, station.stopId, endpoint, waypointNames) &&
                        declaredDestinationMatches(destination, trip) &&
                        trainOperationMatches(arrival.trainType, trip.operationType)
                        && !positionBeyondBoarding(arrival,trip,station.stopId) && arrival.arrivalCode != "2"
                }
                allCandidates += candidates.map { ProvenTimetableArrival(arrival, table, it, calendarDay) }
            }
            // Realtime train numbers and publisher timetable numbers are not always the
            // same identity (for example, a reported 6196 may not be timetable K6196).
            // If there is no usable position estimate by number, anchor an explicitly
            // reported upstream station to actual timetable calls instead. Every candidate
            // still has to pass the route, direction, destination and timestamp checks in
            // estimatePosition; uncertainty is retained as an interval below.
            val canUsePosition = !arrival.snapshot.hasUsableOfficialEta(nowEpochMillis) &&
                arrival.arrivalCode !in setOf("0", "1", "2") &&
                arrival.gatewayContract == SubwayGatewayContract.RAW_FIELDS &&
                (validTimetableText(arrival.reportedStationName.orEmpty()) ||
                    reportedTimetableStationName(arrival.officialArrivalMessage) != null ||
                    reportedTimetableStationCount(arrival.officialArrivalMessage) != null) &&
                arrival.sourceReceivedAtEpochMillis?.let {
                    nowEpochMillis - it in 0L..policy.maxObservationAgeMillis
                } == true
            if (canUsePosition && allCandidates.none {
                    estimatePosition(it, station, nowEpochMillis, policy) != null
                }) {
                for (calendarDay in calendarDays) {
                    val table = stationLoader?.invoke(station.routeId, station.stopId, calendarDay.serviceDate,
                        calendarDay.dayType, calendarDay.sourceVersion, false) ?: continue
                    if (!table.isStructurallyValid() || table.identityAuthority != "seoul") continue
                    val stationCandidates = table.trips.asSequence().filter { trip ->
                        trip.routeId == station.routeId && trip.serviceDate == calendarDay.serviceDate &&
                            trip.dayType == calendarDay.dayType &&
                            trip.directionId == arrival.identity.directionId &&
                            provesBoardingPath(trip, station.stopId, endpoint, waypointNames) &&
                            declaredDestinationMatches(destination, trip) &&
                            trainOperationMatches(arrival.trainType, trip.operationType) &&
                            !positionBeyondBoarding(arrival, trip, station.stopId)
                    }.map { ProvenTimetableArrival(arrival, table, it, calendarDay) }
                        .filter { estimatePosition(it, station, nowEpochMillis, policy) != null }
                        .toList()
                    allCandidates += stationCandidates
                }
            }
            // A route/destination narrows a publisher-provided train record. It never turns a
            // repeated train number or unresolved branch into a choice by array order.
            proofs += allCandidates
            // A sound official ETA needs a proven destination direction, not a timetable
            // vehicle-ID match. Missing optional train numbers must not erase that ETA.
            if (allCandidates.size != 1 && arrival.snapshot.hasUsableOfficialEta(nowEpochMillis)) {
                val table = stationLoader?.invoke(station.routeId, station.stopId, serviceDate,
                    dayType, calendarSourceVersion, false)
                if (table != null && table.isStructurallyValid() && table.trips.any { trip ->
                        trip.directionId == arrival.identity.directionId &&
                            provesBoardingPath(trip, station.stopId, endpoint, waypointNames) &&
                            declaredDestinationMatches(destination, trip) &&
                            trainOperationMatches(arrival.trainType, trip.operationType) &&
                            !positionBeyondBoarding(arrival,trip,station.stopId)
                    }) realtimeMatches += arrival
            }
        }
        val matchingArrivals = (proofs.map { it.arrival } + realtimeMatches).distinct()
        if (matchingArrivals.isEmpty()) {
            val scheduled = scheduleFallback()
            if (scheduled.isNotEmpty()) return result(estimates = scheduled, verified = true,
                message = scheduled.first().estimate.label)
            return result(message = "공식 시간표에서 열차 번호·정차 경로·하차역을 함께 확인하지 못했어요.")
        }
        if (matchingArrivals.map { it.identity.directionId }.distinct().size != 1) {
            val scheduled = scheduleFallback()
            if (scheduled.isNotEmpty()) return result(estimates = scheduled, verified = true,
                message = scheduled.first().estimate.label)
            return result(message = "하차역에 도달하는 방향이 여러 개예요. 첫 구간의 경유역을 더 확인해 주세요.")
        }
        val estimates = proofs.groupBy { Triple(it.arrival.identity,it.arrival.trainId,it.arrival.officialSlot) }
            .values.mapNotNull { candidates ->
                val possibilities = candidates.mapNotNull { proof -> estimatePosition(proof, station, nowEpochMillis, policy) }
                    .distinctBy { listOf(it.serviceDate,it.trainNo,it.routePatternId,it.positionStopId) }
                if (possibilities.isEmpty()) return@mapNotNull null
                val first = possibilities.minBy { it.earliestArrivalAtEpochMillis }
                val arrival = candidates.first().arrival
                val names = possibilities.map { it.trainNo }.distinct().sorted()
                val paths = possibilities.map { it.routePatternId }.distinct().sorted()
                val combined = first.copy(
                    trainNo = if (names.size > 1)
                        "position-slot:${arrival.officialSlot}:${arrival.sourceReceivedAtEpochMillis}" else first.trainNo,
                    routePatternId = paths.singleOrNull() ?: "candidate-paths:${paths.joinToString(",")}",
                    operationType = possibilities.map { it.operationType }.distinct().singleOrNull() ?: OfficialTrainOperationType.OTHER,
                    positionStopId = possibilities.map { it.positionStopId }.distinct().singleOrNull().orEmpty(),
                    earliestArrivalAtEpochMillis = possibilities.minOf { it.earliestArrivalAtEpochMillis },
                    latestArrivalAtEpochMillis = possibilities.maxOf { it.latestArrivalAtEpochMillis },
                    validUntilEpochMillis = possibilities.maxOf { it.validUntilEpochMillis },
                    minimumObservedDelayMillis = possibilities.minOf { it.minimumObservedDelayMillis },
                    maximumObservedDelayMillis = possibilities.maxOf { it.maximumObservedDelayMillis },
                    candidateTrainNumbers = if (possibilities.size > 1) names else emptyList(),
                    candidateServiceDates = possibilities.map { it.serviceDate }.distinct().sorted())
                LinkedSubwayTimetableEstimate(arrival, combined)
            }.sortedBy { it.estimate.earliestArrivalAtEpochMillis }
        val message = when {
            estimates.isNotEmpty() -> estimates.first().estimate.label
            proofs.any { it.arrival.arrivalCode == "2" } -> "해당 열차는 탑승역을 출발했어요. 다음 열차 정보를 기다려 주세요."
            proofs.any { it.arrival.arrivalCode in setOf("0", "1") } -> "열차가 탑승역에 진입·도착 중이에요. 걷기 시간으로 탑승을 판단하지 않아요."
            else -> "진행 방향은 확인했어요. 현재 열차 위치와 시간표를 대조할 수 있는 관측을 기다려요."
        }
        val selectedEstimates = estimates.ifEmpty { scheduleFallback() }
        return result(matchingArrivals, selectedEstimates, true,
            selectedEstimates.firstOrNull()?.estimate?.label ?: message)
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
    Regex("^(?:\\[)?[0-9]+(?:])?\\s*번째\\s*전역\\s*\\(([^()\\r\\n]+)\\)$").matchEntire(message.trim())
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
    return liveDigits.trimStart('0').ifEmpty { "0" } == scheduleDigits.trimStart('0').ifEmpty { "0" }
}

/** Named positions take priority. A station count resolves an actual call sequence, never a fixed minutes-per-stop value. */
internal fun reportedTimetableStationCount(message: String): Int? =
    Regex("(?:\\[)?([0-9]{1,3})(?:])?번째\\s*전역").find(message.trim())?.groupValues?.get(1)
        ?.toIntOrNull()?.takeIf { it in 1..100 }

private fun positionBeyondBoarding(arrival: OfficialSubwayArrival, trip: OfficialTimetableTrip, boardingId: String): Boolean {
    val target = trip.stops.withIndex().singleOrNull { it.value.stopId == boardingId } ?: return false
    val names = listOfNotNull(arrival.reportedStationName, reportedTimetableStationName(arrival.officialArrivalMessage))
        .map(::timetableStationName)
    return trip.stops.withIndex().filter { timetableStationName(it.value.stopName) in names }
        .singleOrNull()?.let { it.index > target.index } == true
}

private fun estimatePosition(proof: ProvenTimetableArrival, station: OfficialSubwayStation,
    now: Long, policy: OfficialTimetableEstimationPolicy): OfficialTimetableEstimate? {
    val arrival = proof.arrival
    if (arrival.arrivalCode in setOf("0", "1", "2")) return null
    val trip = proof.trip
    val target = trip.stops.withIndex().singleOrNull { it.value.stopId == station.stopId } ?: return null
    val names = listOfNotNull(arrival.reportedStationName, reportedTimetableStationName(arrival.officialArrivalMessage))
        .filter(::validTimetableText).map(::timetableStationName).distinct()
    if (names.size > 1) return null // Conflicting provider positions cannot identify one train.
    val position = if (names.isNotEmpty()) {
        // A publisher-supplied station name is evidence, not a hint. Never ignore a
        // conflicting named station and manufacture a different position from [N] stops.
        trip.stops.withIndex().singleOrNull {
            timetableStationName(it.value.stopName) in names && it.index < target.index
        }?.value ?: return null
    } else {
        if (trip.operationType != OfficialTrainOperationType.LOCAL && arrival.trainType != "일반") return null
        val count = reportedTimetableStationCount(arrival.officialArrivalMessage) ?: return null
        trip.stops.getOrNull(target.index-count) ?: return null
    }
    val original = arrival.sourceReceivedAtEpochMillis ?: return null
    val received = arrival.gatewayFetchedAtEpochMillis ?: return null
    if (original > received+60_000L || received > now+60_000L) return null
    val source = minOf(original,received,now)
    val receivedBasis = minOf(received,now)
    val contextualTrip = trip.copy(serviceVariant = arrival.identity.serviceVariant)
    val table = proof.table.copy(trips = listOf(contextualTrip))
    val observation = OfficialTrainPositionObservation("seoul", trip.trainNo, proof.day.serviceDate,
        proof.day.dayType, station.routeId, arrival.identity.directionId,arrival.identity.serviceVariant,
        position.stopId,position.stopName,OfficialTrainPositionKind.POSITION_REPORTED,source,receivedBasis,trip.routePatternId)
    return (estimateOfficialTimetableArrival(table,observation,arrival.identity,now,policy)
        as? OfficialTimetableEstimateResult.Estimated)?.estimate?.copy(originalSourceTimestampEpochMillis = original)
}
