package com.example.run_or_walk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoGuidanceTest {
    private val now = 1_790_000_000_000L
    private val point = MapPoint(126.9726, 37.5547)
    private val boardingPoint = MapPoint(126.9746, 37.5557)
    private val identity = OfficialBoardingIdentity("seoul", "1001000133", "1001", "상행", "소요산행")

    private fun input(
        arrivalAfterMillis: Long = 420_000L,
        nextAfterMillis: Long? = null,
        walkingSeconds: Int = 300,
        bufferSeconds: Int = 120,
        walkingDistanceMeters: Int = (walkingSeconds * 1.25).toInt()
    ) = AutoGuidanceInput(
        location = GuidanceLocation(point, 10.0, now),
        boarding = GuidanceBoarding(identity, boardingPoint, confirmed = true),
        walkingRoute = GuidanceWalkingRoute(point, boardingPoint, identity, walkingSeconds, now, 120_000L, walkingDistanceMeters),
        arrivals = RealtimeArrivalSnapshot(
            identity, now + arrivalAfterMillis, nextAfterMillis?.let { now + it }, now, 60_000L
        ),
        locationPermissionGranted = true,
        bufferSeconds = bufferSeconds
    )

    private fun assertUnknown(input: AutoGuidanceInput, reason: AutoGuidanceUnknownReason) {
        val result = calculateAutoGuidance(input, now)
        assertEquals(AutoGuidanceStatus.UNKNOWN, result.status)
        assertEquals(reason, result.unknownReason)
        assertNull(result.firstArrivalAtEpochMillis)
        assertNull(result.nextArrivalAtEpochMillis)
    }

    @Test
    fun walkingAndFastTravelBoundariesIncludeBufferAtMillisecondPrecision() {
        // 300 seconds walking, 225 seconds estimated fast travel, 120 seconds buffer.
        val cases = listOf(
            420_000L to AutoGuidanceStatus.WALK,
            419_999L to AutoGuidanceStatus.FAST_WALK,
            345_000L to AutoGuidanceStatus.FAST_WALK,
            344_999L to AutoGuidanceStatus.NEXT_VEHICLE
        )
        for ((arrival, expected) in cases) {
            assertEquals(expected, calculateAutoGuidance(input(
                arrivalAfterMillis = arrival, nextAfterMillis = 720_000L), now).status)
        }
    }

    @Test
    fun fastTravelIsAnExplicitRoundedUpEstimate() {
        val result = calculateAutoGuidance(input(walkingSeconds = 301), now)

        assertEquals(301, result.walkingSeconds)
        assertEquals(226, result.fastTravelSeconds)
        assertEquals(6.0, result.fastSpeedKmh, 0.0)
        assertEquals(AutoGuidanceStatus.FAST_WALK, result.status)
        val slower = calculateAutoGuidance(input(), now, AutoGuidancePolicy(fastSpeedKmh = 4.5))
        assertEquals(300, slower.fastTravelSeconds)
    }

    @Test
    fun missingRealWalkingDistanceNeverCreatesAFastWalkDecision() {
        val missing = input().copy(walkingRoute = input().walkingRoute?.copy(distanceMeters = null))
        assertUnknown(missing, AutoGuidanceUnknownReason.WALKING_UNAVAILABLE)
    }

    @Test fun zeroDistanceWithNonzeroWalkingTimeNeverCreatesAnInstantFastWalk() {
        assertUnknown(input(walkingDistanceMeters = 0), AutoGuidanceUnknownReason.WALKING_UNAVAILABLE)
        assertUnknown(input(walkingSeconds = 0, walkingDistanceMeters = 100), AutoGuidanceUnknownReason.WALKING_UNAVAILABLE)
    }

    @Test
    fun nextRecommendationDoesNotInventMissingNextArrival() {
        assertUnknown(input(arrivalAfterMillis = 120_000L),
            AutoGuidanceUnknownReason.NEXT_ARRIVAL_UNAVAILABLE)
    }

    @Test
    fun suppliedNextArrivalIsPreservedExactly() {
        val result = calculateAutoGuidance(input(120_000L, nextAfterMillis = 720_123L), now)

        assertEquals(AutoGuidanceStatus.NEXT_VEHICLE, result.status)
        assertEquals(now + 720_123L, result.nextArrivalAtEpochMillis)
        assertEquals(now, result.arrivalFetchedAtEpochMillis)
    }

    @Test
    fun nextTrainMustBeCatchableAtNormalWalkingSpeedIncludingBuffer() {
        assertEquals(AutoGuidanceStatus.NEXT_VEHICLE,
            calculateAutoGuidance(input(120_000L, nextAfterMillis = 420_000L), now).status)
        assertUnknown(input(120_000L, nextAfterMillis = 419_999L),
            AutoGuidanceUnknownReason.NO_CATCHABLE_TRAIN)
        // A next train reachable only at estimated fast-walking pace is not recommended.
        assertUnknown(input(120_000L, nextAfterMillis = 345_000L),
            AutoGuidanceUnknownReason.NO_CATCHABLE_TRAIN)
    }

    @Test
    fun firstArrivalIsAbsoluteAndCountsDownWithoutInventingAReplacement() {
        val original = input()
        val fresh = calculateAutoGuidance(original, now)
        val thirtySecondsLater = calculateAutoGuidance(original, now + 30_000L)

        assertEquals(AutoGuidanceStatus.WALK, fresh.status)
        assertEquals(AutoGuidanceStatus.FAST_WALK, thirtySecondsLater.status)
        assertEquals(fresh.firstArrivalAtEpochMillis, thirtySecondsLater.firstArrivalAtEpochMillis)
        assertNull(thirtySecondsLater.nextArrivalAtEpochMillis)
    }

    @Test
    fun missingOrFailedArrivalNeverUsesManualOrOldSuccessfulEstimate() {
        val original = input()
        assertUnknown(original.copy(arrivals = null), AutoGuidanceUnknownReason.ARRIVAL_UNAVAILABLE)
        assertUnknown(original.copy(arrivalRequestFailed = true), AutoGuidanceUnknownReason.ARRIVAL_FAILED)
        assertUnknown(original.copy(arrivals = original.arrivals!!.copy(reliable = false)),
            AutoGuidanceUnknownReason.ARRIVAL_UNCERTAIN)
    }

    @Test
    fun providerValidityIsRespectedAtItsExactBoundary() {
        val original = input()
        val arrival = original.arrivals!!.copy(fetchedAtEpochMillis = now - 15_000L, validForMillis = 15_000L)
        assertEquals(AutoGuidanceStatus.WALK,
            calculateAutoGuidance(original.copy(arrivals = arrival), now).status)
        assertUnknown(original.copy(arrivals = arrival.copy(fetchedAtEpochMillis = now - 15_001L)),
            AutoGuidanceUnknownReason.ARRIVAL_STALE)
    }

    @Test
    fun longProviderValidityCannotBypassMaximumArrivalAge() {
        val original = input()
        assertUnknown(original.copy(arrivals = original.arrivals!!.copy(
            fetchedAtEpochMillis = now - 60_001L, validForMillis = 600_000L
        )), AutoGuidanceUnknownReason.ARRIVAL_STALE)
    }

    @Test
    fun futureArrivalFetchAndSecondsMistakenForMillisAreRejected() {
        val original = input()
        assertUnknown(original.copy(arrivals = original.arrivals!!.copy(fetchedAtEpochMillis = now + 1L)),
            AutoGuidanceUnknownReason.ARRIVAL_FUTURE_TIMESTAMP)
        for (timestamp in listOf(now / 1_000L, -1L)) {
            assertUnknown(original.copy(arrivals = original.arrivals!!.copy(fetchedAtEpochMillis = timestamp)),
                AutoGuidanceUnknownReason.ARRIVAL_STALE)
        }
    }

    @Test
    fun everyUntrustedSourceStateOverridesApparentlyUsableEta() {
        val original = input()
        val cases = mapOf(
            ArrivalTrustState.STALE to AutoGuidanceUnknownReason.ARRIVAL_STALE,
            ArrivalTrustState.FUTURE_TIMESTAMP to AutoGuidanceUnknownReason.ARRIVAL_FUTURE_TIMESTAMP,
            ArrivalTrustState.INVALID_ETA to AutoGuidanceUnknownReason.ARRIVAL_INVALID_ETA,
            ArrivalTrustState.UNKNOWN to AutoGuidanceUnknownReason.ARRIVAL_UNCERTAIN
        )
        for ((trust, reason) in cases) {
            assertUnknown(original.copy(arrivals = original.arrivals!!.copy(trustState = trust)), reason)
        }
    }

    @Test
    fun zeroSecondEtaWithAmbiguousArrivalStateCannotGiveGuidance() {
        val original = input(arrivalAfterMillis = 0, walkingSeconds = 0, bufferSeconds = 0)
        assertUnknown(original.copy(arrivals = original.arrivals!!.copy(
            trustState = ArrivalTrustState.UNKNOWN)), AutoGuidanceUnknownReason.ARRIVAL_UNCERTAIN)
        assertUnknown(original.copy(arrivals = original.arrivals.copy(
            reliable = false)), AutoGuidanceUnknownReason.ARRIVAL_UNCERTAIN)
    }

    @Test
    fun observedKoreanTimestampRemainsFutureAfterTimezoneConversion() {
        val fetchedAt = java.time.Instant.parse("2026-10-24T21:40:52.482Z").toEpochMilli()
        val generatedAt = java.time.LocalDateTime.parse("2026-10-25T06:45:37")
            .toInstant(java.time.ZoneOffset.ofHours(9)).toEpochMilli()
        assertEquals(284_518L, generatedAt - fetchedAt)
        val original = input()
        val shifted = original.copy(
            location = original.location!!.copy(observedAtEpochMillis = fetchedAt),
            walkingRoute = original.walkingRoute!!.copy(fetchedAtEpochMillis = fetchedAt),
            arrivals = original.arrivals!!.copy(
                fetchedAtEpochMillis = generatedAt,
                firstArrivalAtEpochMillis = generatedAt + 420_000L))
        val result = calculateAutoGuidance(shifted, fetchedAt)
        assertEquals(AutoGuidanceStatus.UNKNOWN, result.status)
        assertEquals(AutoGuidanceUnknownReason.ARRIVAL_FUTURE_TIMESTAMP, result.unknownReason)
        assertNull(result.firstArrivalAtEpochMillis)
    }

    @Test
    fun expiredFirstArrivalAndInvalidNextArrivalAreNotPresentedAsLive() {
        val original = input()
        assertUnknown(original.copy(arrivals = original.arrivals!!.copy(firstArrivalAtEpochMillis = now - 1L)),
            AutoGuidanceUnknownReason.ARRIVAL_STALE)
        assertUnknown(original.copy(arrivals = original.arrivals.copy(
            nextArrivalAtEpochMillis = original.arrivals.firstArrivalAtEpochMillis
        )), AutoGuidanceUnknownReason.ARRIVAL_UNCERTAIN)
        assertUnknown(original.copy(arrivals = original.arrivals.copy(validForMillis = 0)),
            AutoGuidanceUnknownReason.ARRIVAL_STALE)
    }

    @Test
    fun officialAuthorityStopRouteDirectionAndDestinationMustAllMatch() {
        val original = input()
        val mismatches = listOf(
            identity.copy(authority = "other-city"), identity.copy(stopId = "opposite-platform"),
            identity.copy(routeId = "1004"), identity.copy(directionId = "하행"),
            identity.copy(serviceVariant = "인천행")
        )
        for (wrongIdentity in mismatches) {
            assertUnknown(original.copy(arrivals = original.arrivals!!.copy(identity = wrongIdentity)),
                AutoGuidanceUnknownReason.ARRIVAL_MISMATCH)
        }
    }

    @Test
    fun unconfirmedOrIncompleteOfficialIdentityCannotProduceGuidance() {
        val original = input()
        assertUnknown(original.copy(boarding = original.boarding!!.copy(confirmed = false)),
            AutoGuidanceUnknownReason.BOARDING_UNCONFIRMED)
        assertUnknown(original.copy(boarding = original.boarding.copy(identity = identity.copy(stopId = ""))),
            AutoGuidanceUnknownReason.BOARDING_UNCONFIRMED)
        assertUnknown(original.copy(boarding = null), AutoGuidanceUnknownReason.BOARDING_UNCONFIRMED)
    }

    @Test
    fun deniedPermissionMissingLocationAndBadAccuracyRemainUnknown() {
        val original = input()
        assertUnknown(original.copy(locationPermissionGranted = false),
            AutoGuidanceUnknownReason.LOCATION_PERMISSION_REQUIRED)
        assertUnknown(original.copy(location = null), AutoGuidanceUnknownReason.LOCATION_UNAVAILABLE)
        for (accuracy in listOf(50.01, Double.NaN, Double.POSITIVE_INFINITY, -1.0)) {
            assertUnknown(original.copy(location = original.location!!.copy(accuracyMeters = accuracy)),
                AutoGuidanceUnknownReason.LOCATION_INACCURATE)
        }
        assertEquals(AutoGuidanceStatus.WALK,
            calculateAutoGuidance(original.copy(location = original.location!!.copy(accuracyMeters = 50.0)), now).status)
    }

    @Test
    fun oldAndFutureGpsReadingsAreRejected() {
        val original = input()
        for (timestamp in listOf(now - 30_001L, now + 1L)) {
            assertUnknown(original.copy(location = original.location!!.copy(observedAtEpochMillis = timestamp)),
                AutoGuidanceUnknownReason.LOCATION_STALE)
        }
        assertEquals(AutoGuidanceStatus.WALK, calculateAutoGuidance(original.copy(
            location = original.location!!.copy(observedAtEpochMillis = now - 30_000L)
        ), now).status)
    }

    @Test
    fun invalidGpsCoordinatesAreRejected() {
        val original = input()
        for (badPoint in listOf(MapPoint(Double.NaN, 37.0), MapPoint(181.0, 37.0), MapPoint(127.0, 91.0))) {
            assertUnknown(original.copy(location = original.location!!.copy(point = badPoint)),
                AutoGuidanceUnknownReason.LOCATION_INACCURATE)
        }
    }

    @Test
    fun missingOldOrFutureWalkingResponseIsRejected() {
        val original = input()
        assertUnknown(original.copy(walkingRoute = null), AutoGuidanceUnknownReason.WALKING_UNAVAILABLE)
        for (timestamp in listOf(now - 120_001L, now + 1L)) {
            assertUnknown(original.copy(walkingRoute = original.walkingRoute!!.copy(fetchedAtEpochMillis = timestamp)),
                AutoGuidanceUnknownReason.WALKING_STALE)
        }
        assertUnknown(original.copy(walkingRoute = original.walkingRoute!!.copy(
            fetchedAtEpochMillis = now - 5_001L, validForMillis = 5_000L
        )), AutoGuidanceUnknownReason.WALKING_STALE)
    }

    @Test
    fun movingGpsInvalidatesWalkingTimeFromOldOrigin() {
        val original = input()
        assertUnknown(original.copy(location = original.location!!.copy(point = MapPoint(126.9826, 37.5547))),
            AutoGuidanceUnknownReason.WALKING_MISMATCH)
    }

    @Test
    fun changedBoardingCoordinatesOrOfficialIdentityInvalidateWalkingTime() {
        val original = input()
        assertUnknown(original.copy(walkingRoute = original.walkingRoute!!.copy(
            destination = MapPoint(126.9846, 37.5557)
        )), AutoGuidanceUnknownReason.WALKING_MISMATCH)
        assertUnknown(original.copy(walkingRoute = original.walkingRoute.copy(
            boardingIdentity = identity.copy(directionId = "하행")
        )), AutoGuidanceUnknownReason.WALKING_MISMATCH)
    }

    @Test
    fun invalidDurationsAndPolicyReturnUnknownWithoutThrowing() {
        assertUnknown(input(walkingSeconds = -1), AutoGuidanceUnknownReason.INVALID_INPUT)
        assertUnknown(input(bufferSeconds = -1), AutoGuidanceUnknownReason.INVALID_INPUT)
        for (speed in listOf(0.0, -0.75, 12.01, Double.NaN)) {
            assertEquals(AutoGuidanceStatus.UNKNOWN,
                calculateAutoGuidance(input(), now, AutoGuidancePolicy(fastSpeedKmh = speed)).status)
        }
    }

    @Test
    fun vibrationRequiresExplicitConsentAndNeverUsesUnknownOrIncompleteIdentity() {
        val previous = AutoVibrationState()
        assertFalse(decideAutoGuidanceVibration(previous, false, identity, AutoGuidanceStatus.FAST_WALK, now).shouldVibrate)
        assertFalse(decideAutoGuidanceVibration(previous, true, identity, AutoGuidanceStatus.UNKNOWN, now).shouldVibrate)
        assertFalse(decideAutoGuidanceVibration(previous, true, null, AutoGuidanceStatus.FAST_WALK, now).shouldVibrate)
        assertFalse(decideAutoGuidanceVibration(previous, true, identity.copy(routeId = ""),
            AutoGuidanceStatus.FAST_WALK, now).shouldVibrate)
    }

    @Test
    fun refreshOfSameStateDoesNotVibrateEvenAfterCooldown() {
        val first = decideAutoGuidanceVibration(AutoVibrationState(), true, identity, AutoGuidanceStatus.FAST_WALK, now)
        assertTrue(first.shouldVibrate)
        val repeated = decideAutoGuidanceVibration(first.state, true, identity, AutoGuidanceStatus.FAST_WALK, now + 120_000L)
        assertFalse(repeated.shouldVibrate)
        assertEquals(now, repeated.state.lastVibratedAtEpochMillis)
    }

    @Test
    fun urgencyTransitionsAndNewOfficialSelectionsRespectGlobalCooldown() {
        val first = decideAutoGuidanceVibration(AutoVibrationState(), true, identity, AutoGuidanceStatus.WALK, now)
        val tooSoon = decideAutoGuidanceVibration(first.state, true, identity, AutoGuidanceStatus.FAST_WALK, now + 29_999L)
        assertFalse(tooSoon.shouldVibrate)
        val repeated = decideAutoGuidanceVibration(tooSoon.state, true, identity, AutoGuidanceStatus.FAST_WALK, now + 30_000L)
        assertFalse(repeated.shouldVibrate)
        val next = decideAutoGuidanceVibration(repeated.state, true, identity, AutoGuidanceStatus.NEXT_VEHICLE, now + 30_000L)
        assertTrue(next.shouldVibrate)
        val newSelection = decideAutoGuidanceVibration(next.state, true, identity.copy(directionId = "하행"),
            AutoGuidanceStatus.NEXT_VEHICLE, now + 60_000L)
        assertTrue(newSelection.shouldVibrate)
    }

    @Test
    fun unknownDuringRefreshDoesNotResetVibrationDeduplication() {
        val first = decideAutoGuidanceVibration(AutoVibrationState(), true, identity, AutoGuidanceStatus.FAST_WALK, now)
        val unknown = decideAutoGuidanceVibration(first.state, true, identity, AutoGuidanceStatus.UNKNOWN, now + 30_000L)
        val recovered = decideAutoGuidanceVibration(unknown.state, true, identity, AutoGuidanceStatus.FAST_WALK, now + 60_000L)
        assertFalse(unknown.shouldVibrate)
        assertFalse(recovered.shouldVibrate)
        assertEquals(first.state, recovered.state)
    }

    @Test
    fun equalProviderWalkingTimesButDifferentRealDistancesProduceDifferentFastEstimates() {
        val nearby = calculateAutoGuidance(input(walkingDistanceMeters = 200), now)
        val farther = calculateAutoGuidance(input(walkingDistanceMeters = 375), now)
        assertEquals(120, nearby.fastTravelSeconds)
        assertEquals(225, farther.fastTravelSeconds)
        assertEquals(300, nearby.walkingSeconds)
        assertEquals(300, farther.walkingSeconds)
    }

    @Test
    fun backwardClockDoesNotBypassVibrationCooldown() {
        val first = decideAutoGuidanceVibration(AutoVibrationState(), true, identity, AutoGuidanceStatus.WALK, now)
        val backward = decideAutoGuidanceVibration(first.state, true, identity, AutoGuidanceStatus.FAST_WALK, now - 1L)
        assertFalse(backward.shouldVibrate)
    }
}
