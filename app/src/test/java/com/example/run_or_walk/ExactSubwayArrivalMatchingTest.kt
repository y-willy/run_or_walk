package com.example.run_or_walk

import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ExactSubwayArrivalMatchingTest {
    private val now = 1_790_000_000_000L
    private val identity = OfficialBoardingIdentity("seoul-subway", "1002000222", "1002", "외선", "성수행")

    private fun arrival(
        trainId: String,
        afterMillis: Long,
        binding: OfficialBoardingIdentity = identity
    ) = OfficialSubwayArrival(binding, "외선 · 성수행", "2호선", trainId,
        RealtimeArrivalSnapshot(binding, now + afterMillis,
            fetchedAtEpochMillis = now, validForMillis = 30_000L))

    @Test
    fun absentOrIncompleteVerifiedBindingCannotMatchAStationName() {
        val options = listOf(arrival("123", 300_000L))
        assertNull(chooseMatchingSubwayArrival(null, options, now))
        assertNull(chooseMatchingSubwayArrival(identity.copy(directionId = ""), options, now))
        assertNull(chooseMatchingSubwayArrival(identity.copy(serviceVariant = ""), options, now))
    }

    @Test
    fun matchRequiresEveryOfficialIdentityFieldBeforeChoosingEarliestActualTrain() {
        val expected = arrival("123", 300_000L)
        val mismatches = listOf(
            identity.copy(authority = "other"), identity.copy(stopId = "other-station"),
            identity.copy(routeId = "1077"), identity.copy(directionId = "내선"),
            identity.copy(serviceVariant = "신도림행")
        ).mapIndexed { index, wrong -> arrival("wrong-$index", 1_000L, wrong) }
        val options = mismatches + arrival("456", 600_000L) + expected
        assertSame(expected, chooseMatchingSubwayArrival(identity, options, now))
    }

    @Test
    fun mismatchingSnapshotIdentityMissingTrainIdAndUntrustedTimesAreExcluded() {
        val valid = arrival("valid", 600_000L)
        val original = arrival("bad", 300_000L)
        val invalid = listOf(
            original.copy(trainId = ""),
            original.copy(snapshot = original.snapshot.copy(identity = identity.copy(directionId = "내선"))),
            original.copy(snapshot = original.snapshot.copy(reliable = false)),
            original.copy(snapshot = original.snapshot.copy(fetchedAtEpochMillis = now + 1L)),
            original.copy(snapshot = original.snapshot.copy(fetchedAtEpochMillis = now - 30_001L)),
            original.copy(snapshot = original.snapshot.copy(firstArrivalAtEpochMillis = now - 1L))
        ) + ArrivalTrustState.entries.filter { it != ArrivalTrustState.VALID }.map {
            original.copy(snapshot = original.snapshot.copy(trustState = it))
        }
        assertNull(chooseMatchingSubwayArrival(identity, invalid, now))
        assertSame(valid, chooseMatchingSubwayArrival(identity, invalid + valid, now))
    }

    @Test
    fun conflictingVehicleObservationsAndTiedVehicleTimesDoNotResolveByListOrder() {
        val original = arrival("123", 300_000L)
        assertSame(original, chooseMatchingSubwayArrival(identity, listOf(original, original), now))
        assertNull(chooseMatchingSubwayArrival(identity,
            listOf(original, arrival("123", 310_000L)), now))
        assertNull(chooseMatchingSubwayArrival(identity,
            listOf(original, arrival("456", 300_000L)), now))
    }
}
