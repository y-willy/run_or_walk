package com.example.run_or_walk

import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GuidanceContinuitySimulationTest {
    @Test fun tenMinutesOfCountdownDelayedResponsesJitterAndVehiclePromotionHaveNoUnnecessaryUnknown() {
        val epoch=1_800_000_000_000L
        val origin=MapPoint(127.0,37.0)
        fun point(north:Double,east:Double=0.0)=MapPoint(origin.x+east/(111195*kotlin.math.cos(Math.toRadians(37.0))),origin.y+north/111195)
        val end=point(420.0)
        val route=OfficialSeoulBusRoute("100000001","100000002","종로02","4","종각역","2") // synthetic IDs
        val id=route.identity
        val settings=JourneySettings(useProviderWalkingTime=true,gpsAutoCalibrationEnabled=false)
        val screen=JourneyScreenState(FeatureMode.ROUTE).apply {
            selectedBusStop=OfficialSeoulBusStop(route.stopId,"01001","모의 승차점",end);selectedBusRoute=route
        }
        fun responses(second:Int):List<OfficialSeoulBusArrival> {
            val at=epoch+second*1000L
            val vehicles=if(second<420) listOf(420,900) else listOf(900,1380)
            return vehicles.mapIndexed {index,arrival -> OfficialSeoulBusArrival(id,"vehicle-$arrival",route.name,route.direction,
                RealtimeArrivalSnapshot(id,epoch+arrival*1000L,fetchedAtEpochMillis=at,validForMillis=60000,receivedAtEpochMillis=at),
                gatewayFetchedAtEpochMillis=at,officialSlot=index+1,sourceEtaSeconds=(arrival-second).toLong()) }
        }
        screen.applyBusArrivals(responses(0),epoch)
        var tracker=WalkingRouteTracker(origin,KakaoWalkingRoute(420,300,listOf(origin,end),origin,end),epoch)
        var live=LiveWalkingEvidence(GuidanceLocation(origin,3.0,epoch,0.0,0.2),
            GuidanceWalkingRoute(origin,end,id,300,epoch,120000,420),420,300,listOf(origin,end),epoch)
        data class Pending(val requested:Int,val at:Int,val origin:MapPoint,val meters:Int,val fails:Boolean)
        var pending:Pending?=null
        var unknown=0;var missingPair=0;var promotions=0;var previousVehicle:String?=null
        val trace=mutableListOf<String>()
        for(second in 0..600) {
            val now=epoch+second*1000L
            if(second>0 && second%30==0) {
                val requestOrigin=live.location?.point ?: origin
                val meters=(420.0-(second-20).coerceIn(0,80)).toInt()
                pending=Pending(second,second+7,requestOrigin,meters,second/30%4==3)
            }
            pending?.takeIf {it.at==second}?.let {request ->
                if(!request.fails) {
                    tracker=WalkingRouteTracker(request.origin,KakaoWalkingRoute(request.meters,
                        kotlin.math.ceil(request.meters*300.0/420).toInt(),listOf(request.origin,end),request.origin,end),now)
                    live=updateLiveWalkingEvidence(live,tracker,live.location,id,end,settings,null,now)
                    val fresh=responses(second).let {if(second/30%2==1) it.take(1) else it}
                    screen.applyBusArrivals(fresh,now)
                }
                pending=null
            }
            val fix=if(second%4==0) GuidanceLocation(point((second-20).coerceIn(0,80).toDouble(),if(second==84) 30.0 else 0.0),
                if(second==84) 15.0 else 3.0,now,if(second in 20..100 && second!=84) 1.0 else 0.0,0.2) else null
            live=updateLiveWalkingEvidence(live,tracker,fix,id,end,settings,null,now,
                walkingCalculationDue(live.calculatedAtEpochMillis,now))
            val arrival=screen.currentBusArrival(now)
            val assessment=calculateMovementGuidance(AutoGuidanceInput(live.location,GuidanceBoarding(id,end,true),
                live.walking,arrival?.snapshot,true),now,settings)
            val display=live.walking?.displayMetrics(live.location,end,now)
            if(assessment.status==AutoGuidanceStatus.UNKNOWN) {unknown++;trace+="$second:${assessment.unknownReason}"}
            if(display==null || display.distanceMeters!=live.distanceMeters || display.durationSeconds!=assessment.walkingSeconds) missingPair++
            if(previousVehicle!=null && arrival?.vehicleId!=previousVehicle) promotions++
            previousVehicle=arrival?.vehicleId
        }
        val report=JSONObject().put("ticks",601).put("durationSeconds",600).put("unnecessaryUnknownTicks",unknown)
            .put("inconsistentWalkingTicks",missingPair).put("vehiclePromotions",promotions)
            .put("responseDelaySeconds",7).put("failureEveryFourthRequest",true).put("unknownTrace",trace)
        println("CONTINUITY_SIMULATION $report")
        System.getProperty("pacenudge.proofDir")?.let {File(it).apply {mkdirs()}.resolve("simulation.json").writeText(report.toString(2))}
        assertEquals(report.toString(),0,unknown)
        assertEquals(0,missingPair);assertEquals(1,promotions)
        val expiredAt=epoch+800000
        val expired=calculateMovementGuidance(AutoGuidanceInput(GuidanceLocation(live.location!!.point,3.0,expiredAt),
            GuidanceBoarding(id,end,true),live.walking,
            RealtimeArrivalSnapshot(id,expiredAt+600000,fetchedAtEpochMillis=expiredAt,validForMillis=60000,receivedAtEpochMillis=expiredAt),true),expiredAt,settings)
        assertEquals(AutoGuidanceUnknownReason.WALKING_STALE,expired.unknownReason)
    }
}
