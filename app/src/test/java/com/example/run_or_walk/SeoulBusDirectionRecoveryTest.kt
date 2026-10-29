package com.example.run_or_walk

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SeoulBusDirectionRecoveryTest {
    private val route=OfficialSeoulBusRoute("100900121","100900008","종로02","13","종각역YMCA","2")
    private val choice=KakaoTransitChoice(600,0,"종로02","종로2가.탑골공원",MapPoint(127.0,37.0),
        firstTransitType="BUS",alightingStopName="종각역.YMCA",firstLegStopNames=listOf("종로2가.탑골공원","종각역.YMCA"))
    @Test fun routeServiceAuthenticationFailureCanUseOfficialDownstreamStopOrders()=runBlocking {
        val route=OfficialSeoulBusRoute("100900121","100900008","종로02","13","종각역YMCA","2")
        val choice=KakaoTransitChoice(600,0,"종로02","종로2가.탑골공원",MapPoint(127.0,37.0),
            firstTransitType="BUS",alightingStopName="종각역.YMCA",firstLegStopNames=listOf("종로2가.탑골공원","종각역.YMCA"))
        val calls=mutableListOf<String>()
        val repository=SeoulBusRepository("https://example.workers.dev",transport={url ->
            calls+=url.substringAfter(".dev")
            when {
                "/bus/route-stops?" in url -> throw SeoulBusApiException("fixture auth rejection",SeoulBusFailure.ACCESS_DENIED,serverCode="BUS_UPSTREAM_KEY_REJECTED")
                "/bus/stops?" in url -> """{"provider":"seoul-bus","fetchedAt":"2026-10-29T01:00:00Z","stops":[{"stopId":"100000777","arsId":"01077","name":"종각역.YMCA","x":127,"y":37}]}"""
                else -> """{"provider":"seoul-bus","fetchedAt":"2026-10-29T01:00:00Z","stopId":"100000777","routes":[{"routeId":"100900008","name":"종로02","stopOrder":"14","direction":"종각역YMCA","routeType":"2","supported":true}]}"""
            }
        })
        assertTrue(verifySeoulBusBoardingDirection(repository,route,choice))
        assertEquals(3,calls.size)
    }

    @Test fun backwardOrAmbiguousOfficialOccurrencesCannotConfirmDirection()=runBlocking {
        for(orders in listOf(listOf(12),listOf(14,15))) {
            val repository=SeoulBusRepository("https://example.workers.dev",transport={url ->when {
                "/bus/route-stops?" in url -> throw SeoulBusApiException("auth",SeoulBusFailure.ACCESS_DENIED,serverCode="BUS_UPSTREAM_KEY_REJECTED")
                "/bus/stops?" in url -> """{"provider":"seoul-bus","fetchedAt":"2026-10-29T01:00:00Z","stops":[{"stopId":"100000777","arsId":"01077","name":"종각역.YMCA","x":127,"y":37}]}"""
                else -> """{"provider":"seoul-bus","fetchedAt":"2026-10-29T01:00:00Z","stopId":"100000777","routes":[${orders.joinToString {order -> """{"routeId":"100900008","name":"종로02","stopOrder":"$order","routeType":"2","supported":true}"""}}]}"""
            }})
            assertFalse(verifySeoulBusBoardingDirection(repository,route,choice))
        }
    }

    @Test fun requestLimitsKeepTheirRetryAfterAndNeverTriggerAlternateRequests()=runBlocking {
        var calls=0
        val blocked=SeoulBusApiException("rate limited",SeoulBusFailure.REQUEST_LIMIT,
            System.currentTimeMillis()+60000,"BUDGET_EXHAUSTED")
        val repository=SeoulBusRepository("https://example.workers.dev",transport={calls++;throw blocked})
        val error=runCatching {verifySeoulBusBoardingDirection(repository,route,choice)}.exceptionOrNull()
        assertSame(blocked,error);assertEquals(1,calls)
        assertEquals(blocked.retryAfterEpochMillis,(error as SeoulBusApiException).retryAfterEpochMillis)
    }

    @Test fun aSuccessfulButWrongRouteSequenceMustNotBeOverriddenByFallback()=runBlocking {
        var calls=0
        val repository=SeoulBusRepository("https://example.workers.dev",transport={calls++;
            """{"provider":"seoul-bus","fetchedAt":"2026-10-29T01:00:00Z","routeId":"100900008","stops":[{"stopId":"100000777","stopOrder":"12","name":"종각역.YMCA"},{"stopId":"100900121","stopOrder":"13","name":"종로2가.탑골공원"}]}"""})
        assertFalse(verifySeoulBusBoardingDirection(repository,route,choice));assertEquals(1,calls)
    }
}
