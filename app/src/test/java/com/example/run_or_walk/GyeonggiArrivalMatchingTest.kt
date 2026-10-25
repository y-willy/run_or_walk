package com.example.run_or_walk

import org.junit.Assert.*
import org.junit.Test

class GyeonggiArrivalMatchingTest {
    private val now = 1_790_000_000_000L
    private val match = GyeonggiOfficialMatch("200000177", "200000037", "19", "5100", "강남", MapPoint(127.05, 37.25))
    private fun row(sequence: Int = 1, vehicle: String = "123456", seconds: Int = 180, observed: Long = now) =
        GyeonggiBusArrivalDisplay(match.stationId, match.routeId, match.staOrder, match.routeName,
            match.destination, vehicle, seconds, now, observed, ArrivalTrustState.VALID, sequence)

    @Test fun exactOfficialStopRouteOrderConnectsFirstAndSecondDistinctVehicles() {
        val selected = chooseMatchingGyeonggiArrival(match, listOf(row(2, "123457", 360), row()), now)!!
        assertEquals("123456", selected.vehicleId)
        assertEquals(match.identity, selected.snapshot.identity)
        assertEquals(now + 180_000, selected.snapshot.firstArrivalAtEpochMillis)
        assertEquals(now + 360_000, selected.snapshot.nextArrivalAtEpochMillis)
    }
    @Test fun oppositeOrderRouteDestinationAndStationCannotLeakIntoTheChoice() {
        listOf(row().copy(staOrder = "20"), row().copy(routeId = "200000038"),
            row().copy(destination = "광교"), row().copy(stationId = "200000178"),
            row().copy(routeName = "3")).forEach {
            assertNull(chooseMatchingGyeonggiArrival(match, listOf(it), now))
        }
    }
    @Test fun zeroFutureStaleAndUnidentifiedRowsCannotAuthorizeGuidance() {
        listOf(row(seconds = 0), row(vehicle = "unknown"), row().copy(sequence = 0),
            row(observed = now - 60_001), row(observed = now + 1),
            row().copy(fetchedAtMillis = now + 1), row().copy(trust = ArrivalTrustState.UNKNOWN)).forEach {
            assertNull(chooseMatchingGyeonggiArrival(match, listOf(it), now))
        }
    }
    @Test fun duplicateConflictsNeverCreateTwoVehiclesOrChooseTheFirstResponseRow() {
        assertNotNull(chooseMatchingGyeonggiArrival(match, listOf(row(), row()), now))
        assertNull(chooseMatchingGyeonggiArrival(match, listOf(row(), row(seconds = 190)), now))
        assertNull(chooseMatchingGyeonggiArrival(match, listOf(row(), row(2)), now))
        assertNull(chooseMatchingGyeonggiArrival(match, listOf(row(), row(vehicle = "123457")), now))
    }
    @Test fun oldSecondPredictionCapsBothVehiclesExpiryWithoutRenewingSourceTime() {
        val selected = chooseMatchingGyeonggiArrival(match,
            listOf(row(), row(2, "123457", 360, now - 50_000)), now)!!
        assertEquals(now, selected.snapshot.fetchedAtEpochMillis)
        assertEquals(10_000L, selected.snapshot.validForMillis)
    }
    @Test fun aPassedVehicleCanAdvanceOnlyToAnActuallyReportedNextVehicle() {
        val selected = chooseMatchingGyeonggiArrival(match,
            listOf(row(seconds = 1, observed = now - 2_000), row(2, "123457", 360)), now)!!
        assertEquals("123457", selected.vehicleId)
        assertNull(selected.snapshot.nextArrivalAtEpochMillis)
        assertNull(chooseMatchingGyeonggiArrival(match, listOf(row(seconds = 1, observed = now - 2_000)), now))
    }
}
