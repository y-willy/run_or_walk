package com.example.run_or_walk

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class OfficialTimetableEstimationTest {
    private val date = LocalDate.of(2026, 10, 25)
    private fun time(value: String) = officialTimetableTimeAt(date, value)!!
    private val target = OfficialBoardingIdentity("seoul", "mangpo", "1075", "하행", "고색행")
    private val sourceTime = time("10:01:30")
    private val now = sourceTime + 109_000L
    private val source = OfficialTimetableSource("seoulMetro", "OA-22522", "20250930",
        time("00:00:00") - 365L * 86_400_000, scheduleAsOfDate = LocalDate.of(2025, 9, 30))
    private val stops = listOf(
        OfficialTimetableStop(1, "sanggal", "상갈", time("10:00:00"), time("10:00:30")),
        OfficialTimetableStop(2, "cheongmyeong", "청명", time("10:03:00"), time("10:03:30")),
        OfficialTimetableStop(3, "yeongtong", "영통", time("10:06:00"), time("10:06:30")),
        OfficialTimetableStop(4, "mangpo", "망포", time("10:09:00"), time("10:09:30")))
    private val trip = OfficialTimetableTrip("6175", date, OfficialServiceDayType.HOLIDAY,
        "1075", "하행", "고색행", OfficialTrainOperationType.LOCAL, "gosaek-local", stops)
    private val timetable = OfficialTimetable(source, "seoul", listOf(
        OfficialTimetableCalendarDay(date, OfficialServiceDayType.HOLIDAY, "official-calendar-2026")), listOf(trip))
    private val observation = OfficialTrainPositionObservation("seoul", "6175", date,
        OfficialServiceDayType.HOLIDAY, "1075", "하행", "고색행", "sanggal", "상갈",
        OfficialTrainPositionKind.POSITION_REPORTED, sourceTime, now)

    private fun estimate(table: OfficialTimetable = timetable, obs: OfficialTrainPositionObservation = observation,
        at: Long = now, boarding: OfficialBoardingIdentity = target,
        policy: OfficialTimetableEstimationPolicy = OfficialTimetableEstimationPolicy()) =
        estimateOfficialTimetableArrival(table, obs, boarding, at, policy)

    private fun estimated(result: OfficialTimetableEstimateResult): OfficialTimetableEstimate =
        (result as OfficialTimetableEstimateResult.Estimated).estimate

    private fun unknown(result: OfficialTimetableEstimateResult, reason: OfficialTimetableUnknownReason? = null) {
        assertTrue(result.toString(), result is OfficialTimetableEstimateResult.Unknown)
        if (reason != null) assertEquals(reason, (result as OfficialTimetableEstimateResult.Unknown).reason)
    }

    @Test fun aNamedPositionUsesTheExactTrainStopIntervalAndSubtractsOldSourceAge() {
        val result = estimated(estimate())
        assertEquals(time("10:07:30"), result.earliestArrivalAtEpochMillis)
        assertEquals(time("10:10:30"), result.latestArrivalAtEpochMillis)
        assertEquals(251_000L, result.earliestArrivalAtEpochMillis - now)
        assertEquals(-90_000L, result.minimumObservedDelayMillis)
        assertEquals(90_000L, result.maximumObservedDelayMillis)
        assertEquals(sourceTime, result.sourceTimestampEpochMillis)
        assertEquals(109_000L, result.observationAgeMillis)
        assertEquals(OfficialTimetableEstimateSource.OFFICIAL_TIMETABLE_POSITION, result.source)
        assertFalse(result.isRealtime)
        assertTrue(result.scheduleValidityUnconfirmed)
        assertTrue(result.label.contains("2025-09-30"))
        assertTrue(result.label.contains("유효기간 미확인"))
        val later = estimated(estimate(at = now + 20_000))
        assertEquals(result.earliestArrivalAtEpochMillis, later.earliestArrivalAtEpochMillis)
        assertEquals(result.sourceTimestampEpochMillis, later.sourceTimestampEpochMillis)
        assertEquals(129_000L, later.observationAgeMillis)
    }

    @Test fun explicitEventKindsAndConfirmedDwellHaveDifferentAnchors() {
        val arrived = estimated(estimate(obs = observation.copy(positionKind = OfficialTrainPositionKind.ARRIVAL_EVENT)))
        assertEquals(time("10:10:30"), arrived.earliestArrivalAtEpochMillis)
        assertEquals(arrived.earliestArrivalAtEpochMillis, arrived.latestArrivalAtEpochMillis)
        val departed = estimated(estimate(obs = observation.copy(positionKind = OfficialTrainPositionKind.DEPARTURE_EVENT)))
        assertEquals(time("10:10:00"), departed.earliestArrivalAtEpochMillis)
        val dwell = estimated(estimate(obs = observation.copy(positionKind = OfficialTrainPositionKind.AT_STATION)))
        assertEquals(departed.earliestArrivalAtEpochMillis, dwell.earliestArrivalAtEpochMillis)
        assertEquals(arrived.latestArrivalAtEpochMillis, dwell.latestArrivalAtEpochMillis)
    }

    @Test fun stationCountOrUnknownMotionNeverProducesMinutes() {
        unknown(estimate(obs = observation.copy(positionStopId = null, positionStopName = null)),
            OfficialTimetableUnknownReason.POSITION_UNKNOWN)
        unknown(estimate(obs = observation.copy(positionKind = OfficialTrainPositionKind.UNKNOWN)),
            OfficialTimetableUnknownReason.POSITION_UNKNOWN)
        unknown(estimate(obs = observation.copy(positionStopId = null, positionStopName = "[3]번째 전역")),
            OfficialTimetableUnknownReason.POSITION_NOT_FOUND)
    }

    @Test fun exactNamesCanMatchOnlyOneActualCallAndIdsCannotConflictWithNames() {
        assertEquals("sanggal", estimated(estimate(obs = observation.copy(positionStopId = null))).positionStopId)
        unknown(estimate(obs = observation.copy(positionStopId = "yeongtong")),
            OfficialTimetableUnknownReason.POSITION_CONFLICT)
        unknown(estimate(obs = observation.copy(positionStopId = "wrong")),
            OfficialTimetableUnknownReason.POSITION_NOT_FOUND)
        val duplicatedName = trip.copy(stops = stops.map { if (it.stopId == "cheongmyeong") it.copy(stopName = "상갈") else it })
        unknown(estimate(timetable.copy(trips = listOf(duplicatedName)), observation.copy(positionStopId = null)),
            OfficialTimetableUnknownReason.AMBIGUOUS_POSITION)
    }

    @Test fun directionServiceRouteAndTrainNumbersAreNeverInferred() {
        listOf(observation.copy(authority = "korail"), observation.copy(routeId = "1001"),
            observation.copy(directionId = "상행"), observation.copy(serviceVariant = "인천행")).forEach {
            unknown(estimate(obs = it), OfficialTimetableUnknownReason.IDENTITY_MISMATCH)
        }
        listOf("K6175", "06175", "617", "6175A").forEach {
            unknown(estimate(obs = observation.copy(trainNo = it)), OfficialTimetableUnknownReason.TRAIN_NOT_FOUND)
        }
        unknown(estimate(obs = observation.copy(routePatternId = "different-branch")),
            OfficialTimetableUnknownReason.TRAIN_NOT_FOUND)
        val branch = trip.copy(routePatternId = "different-branch")
        unknown(estimate(timetable.copy(trips = listOf(trip, branch))), OfficialTimetableUnknownReason.AMBIGUOUS_TRAIN)
        assertNotNull(estimated(estimate(timetable.copy(trips = listOf(trip, branch)),
            observation.copy(routePatternId = trip.routePatternId))))
    }

    @Test fun holidayCalendarAndServiceDateMustBeOfficiallyConfirmed() {
        unknown(estimate(obs = observation.copy(dayType = OfficialServiceDayType.WEEKDAY)),
            OfficialTimetableUnknownReason.CALENDAR_UNCONFIRMED)
        unknown(estimate(obs = observation.copy(serviceDate = date.plusDays(1))),
            OfficialTimetableUnknownReason.CALENDAR_UNCONFIRMED)
        unknown(estimate(timetable.copy(calendar = emptyList())), OfficialTimetableUnknownReason.INVALID_TIMETABLE)
    }

    @Test fun expressNonStopsPreviousTargetsAndCircularDuplicateCallsCannotMatch() {
        val express = trip.copy(operationType = OfficialTrainOperationType.EXPRESS,
            stops = stops.filter { it.stopId != target.stopId })
        unknown(estimate(timetable.copy(trips = listOf(express))), OfficialTimetableUnknownReason.TARGET_NOT_SERVED)
        unknown(estimate(boarding = target.copy(stopId = "sanggal")), OfficialTimetableUnknownReason.TARGET_NOT_AHEAD)
        val repeated = trip.copy(stops = stops + stops.last().copy(sequence = 5,
            arrivalAtEpochMillis = time("10:12:00"), departureAtEpochMillis = time("10:12:30")))
        unknown(estimate(timetable.copy(trips = listOf(repeated))), OfficialTimetableUnknownReason.AMBIGUOUS_TARGET)
        unknown(estimate(timetable.copy(trips = listOf(trip.copy(stops = stops.map {
            if (it.stopId == "mangpo") it.copy(arrivalAtEpochMillis = null) else it
        })))), OfficialTimetableUnknownReason.ANCHOR_TIME_UNAVAILABLE)
    }

    @Test fun staleFutureAndExcessivelyDelayedPositionsAreRejectedWithoutFreshening() {
        unknown(estimate(at = sourceTime + 300_001), OfficialTimetableUnknownReason.OBSERVATION_STALE)
        assertNotNull(estimated(estimate(at = sourceTime + 300_000)))
        unknown(estimate(obs = observation.copy(receivedAtEpochMillis = now + 1)),
            OfficialTimetableUnknownReason.OBSERVATION_FUTURE)
        unknown(estimate(obs = observation.copy(sourceTimestampEpochMillis = now + 1)),
            OfficialTimetableUnknownReason.OBSERVATION_FUTURE)
        unknown(estimate(policy = OfficialTimetableEstimationPolicy(maxObservationAgeMillis = 60_000)),
            OfficialTimetableUnknownReason.OBSERVATION_STALE)
        val late = time("11:00:00")
        unknown(estimate(obs = observation.copy(sourceTimestampEpochMillis = late, receivedAtEpochMillis = late), at = late),
            OfficialTimetableUnknownReason.DELAY_OUT_OF_RANGE)
    }

    @Test fun reportedPositionCanCoverTheNextStationWithoutDiscardingAnOnTimeTrain() {
        val at = time("10:00:00")
        val result = estimated(estimate(obs = observation.copy(sourceTimestampEpochMillis = at, receivedAtEpochMillis = at), at = at))
        assertEquals(time("10:06:00"), result.earliestArrivalAtEpochMillis)
        assertEquals(time("10:09:00"), result.latestArrivalAtEpochMillis)
        val close = observation.copy(positionStopId = "yeongtong", positionStopName = "영통",
            sourceTimestampEpochMillis = time("10:06:30"), receivedAtEpochMillis = time("10:07:00"))
        val possiblyArrived = estimated(estimate(obs = close, at = time("10:07:00")))
        assertTrue(possiblyArrived.earliestArrivalAtEpochMillis < time("10:07:00"))
        assertTrue(possiblyArrived.latestArrivalAtEpochMillis > time("10:07:00"))
    }

    @Test fun expiredSourcesAndNotYetPublishedVersionsNeverBecomeCurrent() {
        unknown(estimate(timetable.copy(source = source.copy(validThroughServiceDate = date.minusDays(1)))))
        unknown(estimate(timetable.copy(source = source.copy(publishedAtEpochMillis = now + 1))),
            OfficialTimetableUnknownReason.SOURCE_NOT_YET_PUBLISHED)
        val bounded = estimated(estimate(timetable.copy(source = source.copy(
            validFromServiceDate = date, validThroughServiceDate = date))))
        assertFalse(bounded.scheduleValidityUnconfirmed)
    }

    @Test fun serviceDayAfterMidnightUsesThePreviousDatesExplicitTrip() {
        val shifted = trip.copy(stops = stops.map { it.copy(
            arrivalAtEpochMillis = it.arrivalAtEpochMillis!! + 14 * 3_600_000L,
            departureAtEpochMillis = it.departureAtEpochMillis!! + 14 * 3_600_000L) })
        val obs = observation.copy(sourceTimestampEpochMillis = time("24:01:30"),
            receivedAtEpochMillis = time("24:03:19"))
        val result = estimated(estimate(timetable.copy(trips = listOf(shifted)), obs, obs.receivedAtEpochMillis))
        assertEquals(date, result.serviceDate)
        assertEquals(time("24:07:30"), result.earliestArrivalAtEpochMillis)
        unknown(estimate(timetable.copy(trips = listOf(shifted)), obs.copy(serviceDate = date.plusDays(1)),
            obs.receivedAtEpochMillis), OfficialTimetableUnknownReason.CALENDAR_UNCONFIRMED)
    }
}
