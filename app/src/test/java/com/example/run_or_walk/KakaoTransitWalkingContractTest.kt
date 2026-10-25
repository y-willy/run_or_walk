package com.example.run_or_walk

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Official Kakao transit walking segments use WALKING/time. */
class KakaoTransitWalkingContractTest {
    @Test
    fun officialWalkingSegmentKeepsTheFirstBoardingAndDuration() {
        val routes = JSONObject(
            """{"routes":[{"properties":{"totalTime":720,"transfers":0},"steps":[
                {"properties":{"type":"WALKING","time":120}},
                {"properties":{"type":"SUBWAY","vehicles":[{"name":"2호선","id":"1002"}],
                    "stops":[{"name":"강남역","x":127.0276,"y":37.4979}]}}
            ]}]}"""
        ).getJSONArray("routes")

        val choices = parseTransitChoices(routes)

        assertEquals(1, choices.size)
        assertEquals("강남역", choices.single().boardingStopName)
        assertEquals("SUBWAY", choices.single().firstTransitType)
        assertEquals(120, choices.single().totalWalkingSeconds)
        assertEquals(720, choices.single().totalSeconds)
        assertEquals(BoardingPointSource.ESTIMATED, choices.single().boardingSource)
    }

    @Test
    fun carriesSecondListedStopAsAnUnverifiedDirectionHint() {
        val routes = JSONObject(
            """{"routes":[{"properties":{"totalTime":720},"steps":[
                {"properties":{"type":"SUBWAY","vehicles":[{"name":"2호선"}],
                    "stops":[
                        {"name":"강남역","x":127.0276,"y":37.4979},
                        {"name":"역삼역","x":127.0365,"y":37.5006}
                    ]}}
            ]}]}"""
        ).getJSONArray("routes")
        assertEquals("역삼역", parseTransitChoices(routes).single().nextStopName)
    }

    @Test
    fun olderWalkFormatRemainsSupported() {
        val routes = JSONObject(
            """{"routes":[{"properties":{"totalTime":720},"steps":[
                {"properties":{"type":"WALK","totalTime":95}},
                {"properties":{"type":"BUS","vehicles":[{"name":"100번"}],
                    "stops":[{"name":"서울역","x":126.9726,"y":37.5547}]}}
            ]}]}"""
        ).getJSONArray("routes")

        val choices = parseTransitChoices(routes)

        assertEquals(1, choices.size)
        assertEquals(95, choices.single().totalWalkingSeconds)
        assertEquals("서울역", choices.single().boardingStopName)
    }

    @Test
    fun unknownSegmentDoesNotCreateAFakeBoarding() {
        val routes = JSONObject(
            """{"routes":[{"properties":{"totalTime":720},"steps":[
                {"properties":{"type":"TELEPORT","time":1}},
                {"properties":{"type":"BUS","stops":[{"name":"서울역","x":126.9726,"y":37.5547}]}}
            ]}]}"""
        ).getJSONArray("routes")
        assertTrue(parseTransitChoices(routes).isEmpty())
    }
}
