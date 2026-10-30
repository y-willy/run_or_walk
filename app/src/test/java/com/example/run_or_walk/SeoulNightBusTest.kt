package com.example.run_or_walk

import java.io.File
import java.net.URI
import java.net.URLDecoder
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SeoulNightBusTest {
    // Recorded public Worker responses, not a new operating-API call during unit tests.
    private val data get() = JSONObject(File("../app/src/test/resources/n37_official_20261030.json").readText())
    private val stop get() = parseWorkerSeoulBusStops(data.getJSONObject("boardingStops").toString()).single()
    private val route get() = parseWorkerSeoulBusRoutes(data.getJSONObject("boardingRoutes").toString(), stop).single { it.name == "N37" }
    private val choice get() = KakaoTransitChoice(1800, 0, "N37(심야)", "종로2가.삼일교", stop.point,
        firstTransitType = "BUS", alightingStopName = "지하철2호선강남역(중)", firstLegStopNames = listOf(
            "종로2가.삼일교", "명동성당.남대문세무서", "순천향대학병원.한남오거리",
            "신사역.푸른저축은행", "논현역", "지하철2호선강남역(중)"))

    @Test fun recordedOfficialNightTypeIsSupportedEvenWithTheOldWorkerClassification() {
        assertEquals("15", route.routeType)
        assertTrue("Official Seoul night type 15 must not be treated as another provider", route.supported)
        assertEquals("100100585", route.routeId)
        assertEquals("81", route.stopOrder)
    }

    @Test fun recordedNightRouteCanStartMonitoringAndRoundTripTheServiceRequest() {
        val request=JourneyMonitoringRequest(stop.name,requireNotNull(stop.point),route.identity,busStop=stop,busRoute=route)
        assertTrue("A supported official night route must also be accepted by the service",request.isValid())
        assertEquals(request,JourneyMonitoringRequestCodec.decode(JourneyMonitoringRequestCodec.encode(request)))
        assertTrue(FavoriteBus(request).isValid())
    }

    @Test fun verifiedNightBoardingSurvivesSessionRestoreWithoutLosingItsRouteOrDirection() {
        val screens=restoreJourneyScreens(null)
        val officialStop=stop;val officialRoute=route
        screens.getValue(FeatureMode.ROUTE).apply {
            boarding=officialStop.toJourneyPlace();boardingMatchedAutomatically=true;busRoutePathVerified=true
            selectedBusStop=officialStop;selectedBusRoute=officialRoute;confirmedServiceIdentity=officialRoute.identity
        }
        val restored=restoreJourneyScreens(decodeJourneySession(encodeJourneySession(captureJourneySession(FeatureMode.ROUTE,screens))))
            .getValue(FeatureMode.ROUTE)
        assertEquals(route,restored.selectedBusRoute)
        assertEquals(route.identity,restored.confirmedServiceIdentity)
        assertTrue(restored.boardingConfirmed)
    }

    @Test fun kakaoNightLabelMatchesTheOfficialRouteWithoutMergingOtherServices() {
        val official = route.copy(supported = true)
        assertEquals(official, resolveOfficialSeoulBusRoute(listOf(official), stop.stopId, choice.serviceName))
        for (other in listOf("N370", "N37A", "N37(예약)", "37", "동대문A01")) {
            assertNull(resolveOfficialSeoulBusRoute(listOf(official), stop.stopId, other))
        }
        assertEquals("N13", normalizeBusRouteName("n13 (심야)"))
        assertNotEquals(normalizeBusRouteName("동대문01"), normalizeBusRouteName("동대문A01"))
    }

    @Test fun medianStopSuffixDoesNotPreventSearchOrOfficialNameComparison() {
        assertEquals("지하철2호선강남역", normalizeSeoulBusStopQuery("지하철2호선강남역(중)"))
        assertEquals(normalizeBusStopName("지하철2호선강남역"), normalizeBusStopName("지하철2호선강남역(중)"))
        assertNotEquals(normalizeBusStopName("강남역1번출구"), normalizeBusStopName("강남역2번출구"))
    }

    @Test fun recordedDownstreamOrdersConfirmGangnamDirectionDespiteRouteServiceKeyRejection() = runBlocking {
        val calls = mutableListOf<String>()
        val repository = replay(calls)
        assertTrue(verifySeoulBusBoardingDirection(repository, route.copy(supported = true), choice))
        assertTrue(calls.any { "지하철2호선강남역" in it })
        assertFalse(calls.any { "(중)" in it })
        assertEquals(1, calls.count { it.startsWith("/bus/route-stops") })
    }

    @Test fun actualOppositeDirectionOccurrencesMustNotConfirmTheForwardJourney() = runBlocking {
        val reverseStop = OfficialSeoulBusStop("121000012", "22012", "지하철2호선강남역")
        val reverse = parseWorkerSeoulBusRoutes(data.getJSONObject("routesByStopId").getJSONObject(reverseStop.stopId).toString(), reverseStop)
            .single { it.name == "N37" }.copy(supported = true)
        assertEquals("26", reverse.stopOrder)
        val downstream = listOf(OfficialSeoulBusRouteStop(reverse.routeId, reverse.stopId, "26", reverseStop.name),
            OfficialSeoulBusRouteStop(reverse.routeId, "121000014", "27", "논현역"))
        assertFalse(seoulBusRouteReachesAlighting(reverse, choice, downstream))
        val wrongRoute = route.copy(stopOrder = "90", supported = true)
        assertFalse(verifySeoulBusBoardingDirection(replay(mutableListOf()), wrongRoute, choice))
    }

    @Test fun anotherProviderRemainsUnsupportedEvenIfItsNameLooksLikeANightBus() {
        val json = data.getJSONObject("boardingRoutes")
        val rows = json.getJSONArray("routes")
        val row = (0 until rows.length()).map { rows.getJSONObject(it) }.single { it.getString("name") == "N37" }
        row.put("routeType", "8").put("supported", true)
        assertFalse(parseWorkerSeoulBusRoutes(json.toString(), stop).single { it.name == "N37" }.supported)
    }

    private fun replay(calls: MutableList<String>): SeoulBusRepository {
        val fixture = data
        return SeoulBusRepository("https://example.workers.dev", transport = { url ->
            val uri = URI(url)
            val parameters = uri.rawQuery.split("&").associate {
                val pair = it.split("=", limit = 2)
                pair[0] to URLDecoder.decode(pair[1], "UTF-8")
            }
            calls += uri.path + "?" + parameters.values.joinToString()
            when (uri.path) {
                "/bus/route-stops" -> throw SeoulBusApiException("recorded route-service rejection",
                    SeoulBusFailure.ACCESS_DENIED, serverCode = "BUS_UPSTREAM_KEY_REJECTED")
                "/bus/stops" -> fixture.getJSONObject("stopQueries").getJSONObject(parameters.getValue("query")).toString()
                "/bus/routes" -> fixture.getJSONObject("routesByStopId").getJSONObject(parameters.getValue("stopId")).toString()
                else -> error("Unexpected endpoint")
            }
        })
    }
}
