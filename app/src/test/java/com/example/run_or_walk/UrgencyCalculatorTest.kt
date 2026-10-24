package com.example.run_or_walk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class UrgencyCalculatorTest {
    @Test
    fun threeMinutesOfSlackIsNone() {
        assertEquals(Urgency.NONE, calculateUrgency(10.0, 2.0, 300).urgency)
    }

    @Test
    fun oneMinuteOfSlackIsLow() {
        assertEquals(Urgency.LOW, calculateUrgency(8.0, 2.0, 300).urgency)
    }

    @Test
    fun zeroSlackIsMedium() {
        assertEquals(Urgency.MEDIUM, calculateUrgency(7.0, 2.0, 300).urgency)
    }

    @Test
    fun twoMinutesShortIsHigh() {
        assertEquals(Urgency.HIGH, calculateUrgency(5.0, 2.0, 300).urgency)
    }

    @Test
    fun moreThanTwoMinutesShortIsMissed() {
        assertEquals(Urgency.MISSED, calculateUrgency(5.0, 2.0, 301).urgency)
    }

    @Test
    fun oneWalkingSecondCanCrossTheNoneBoundary() {
        assertEquals(Urgency.LOW, calculateUrgency(10.0, 2.0, 301).urgency)
    }

    @Test
    fun oneWalkingSecondCanCrossTheLowBoundary() {
        assertEquals(Urgency.MEDIUM, calculateUrgency(8.0, 2.0, 301).urgency)
    }

    @Test
    fun oneWalkingSecondCanCrossTheMediumBoundary() {
        assertEquals(Urgency.HIGH, calculateUrgency(7.0, 2.0, 301).urgency)
    }

    @Test
    fun resultRetainsTheInputsUsedForManualArrivalGuidance() {
        val result = calculateUrgency(10.5, 1.25, 90)

        assertEquals(10.5, result.arrivalMinutes, 0.0)
        assertEquals(1.25, result.bufferMinutes, 0.0)
        assertEquals(1.5, result.walkingMinutes, 0.0)
        assertEquals(7.75, result.slackMinutes, 0.0)
    }

    @Test
    fun zeroInputsAreValid() {
        val result = calculateUrgency(0.0, 0.0, 0)

        assertEquals(Urgency.MEDIUM, result.urgency)
        assertEquals(0.0, result.slackMinutes, 0.0)
    }

    @Test
    fun arrivalMustBeFiniteAndNonnegative() {
        listOf(-0.1, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)
            .forEach { invalid ->
                assertThrows(IllegalArgumentException::class.java) {
                    calculateUrgency(invalid, 2.0, 300)
                }
            }
    }

    @Test
    fun bufferMustBeFiniteAndNonnegative() {
        listOf(-0.1, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)
            .forEach { invalid ->
                assertThrows(IllegalArgumentException::class.java) {
                    calculateUrgency(10.0, invalid, 300)
                }
            }
    }

    @Test
    fun negativeWalkingTimeIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            calculateUrgency(10.0, 2.0, -1)
        }
    }
}
