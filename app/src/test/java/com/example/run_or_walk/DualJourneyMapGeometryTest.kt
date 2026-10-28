package com.example.run_or_walk

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DualJourneyMapGeometryTest {
    private fun point(x:Double,y:Double)=JSONArray().put(x).put(y)
    private fun step(type:String,name:String="",withPath:Boolean=true):JSONObject {
        val properties=JSONObject().put("type",type).put("time",60)
        if(type!="WALK") properties.put("vehicles",JSONArray().put(JSONObject().put("name",name).put("id",name)))
            .put("stops",JSONArray().put(JSONObject().put("id","a").put("name","서울역").put("x",126.97).put("y",37.55))
                .put(JSONObject().put("id","b").put("name","용산").put("x",126.96).put("y",37.53)))
        return JSONObject().put("properties",properties).apply {
            if(withPath)put("path",JSONObject().put("points",JSONArray().put(point(126.97,37.55)).put(point(126.965,37.54)).put(point(126.96,37.53))))
        }
    }
    private fun parse(steps:JSONArray)=parseTransitChoices(JSONArray().put(JSONObject().put("properties",
        JSONObject().put("totalTime",1200).put("transfers",1)).put("steps",steps))).single()
    @Test fun completeWalkingBusSubwayTransferGeometryIsRetainedAsSeparateSegments() {
        val route=parse(JSONArray().put(step("WALK")).put(step("BUS","273")).put(step("WALK",withPath=false)).put(step("SUBWAY","1호선")))
        assertEquals(3,route.pathSegments.size)
        assertTrue(route.itineraryKey.contains("273"))
        assertTrue(route.itineraryKey.contains("1호선"))
        assertEquals(MapPoint(126.965,37.54),route.pathSegments[1][1])
    }
    @Test fun noProviderGeometryDoesNotBecomeStraightLines() {
        val route=parse(JSONArray().put(step("SUBWAY","1호선",false)))
        assertTrue(route.pathSegments.isEmpty())
        val broken=step("SUBWAY","1호선").apply {getJSONObject("path").getJSONArray("points").put(1,JSONArray().put("invalid").put(37.54))}
        assertTrue(parse(JSONArray().put(broken)).pathSegments.isEmpty())
    }
    @Test fun accessMapRefreshDependsOnGpsAndDepartureAndNotDestinationOrEta() {
        val gps=MapPoint(127.0,37.2);val departure=MapPoint(126.97,37.55)
        assertFalse(shouldRefreshAccessMap(gps,departure,gps,departure,true))
        assertFalse(shouldRefreshAccessMap(gps,departure,MapPoint(127.00001,37.2),departure,true))
        assertTrue(shouldRefreshAccessMap(gps,departure,MapPoint(127.01,37.2),departure,true))
        assertTrue(shouldRefreshAccessMap(gps,departure,gps,MapPoint(126.96,37.53),true))
        assertTrue(shouldRefreshAccessMap(gps,departure,gps,departure,false))
    }
    @Test fun routeRestoreRetainsIdentityWithoutPuttingGeometryIntoMonitoringPayload() {
        val route=parse(JSONArray().put(step("SUBWAY","1호선")))
        val encoded=JourneyTransitRoute("route",route).toJson()
        val restored=encoded.route()!!.kakao
        assertEquals(route.itineraryKey,restored.itineraryKey)
        assertTrue(restored.pathSegments.isEmpty())
        assertTrue(encoded.toString().length<16384)
        assertEquals(route.boardingStopName,restored.boardingStopName)
    }
}
