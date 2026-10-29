package com.example.run_or_walk
import org.junit.Assert.*
import org.junit.Test
class GuidanceTraceTest {
    @Test fun transitionsLogOnlyWhitelistedFieldsAndNeverExactPositionsOrOfficialNames() {
        val lines=mutableListOf<String>();val trace=GuidanceStateTrace("test",lines::add)
        val now=1800000000000L;val point=MapPoint(127.123456,37.123456)
        val id=OfficialBoardingIdentity("seoul-bus","private-stop","private-route","4","private-service")
        val fix=GuidanceLocation(point,3.0,now)
        val walking=GuidanceWalkingRoute(point,point,id,300,now,120000,420)
        val eta=RealtimeArrivalSnapshot(id,now+420000,now+900000,now,60000,receivedAtEpochMillis=now)
        fun record(status:AutoGuidanceStatus,reason:AutoGuidanceUnknownReason?=null,enabled:Boolean=true)=
            trace.record(AutoGuidanceAssessment(status,reason),fix,walking,eta,GuidanceBoarding(id,point,true),
                WalkRoute(420,300),1,2,now,now,enabled)
        record(AutoGuidanceStatus.WALK);record(AutoGuidanceStatus.WALK)
        assertEquals(1,lines.size)
        record(AutoGuidanceStatus.UNKNOWN,AutoGuidanceUnknownReason.WALKING_MISMATCH)
        assertEquals(2,lines.size)
        assertTrue(lines.last().contains("previous=WALK current=UNKNOWN reason=WALKING_MISMATCH"))
        assertTrue(lines.last().contains("inputMeters=420 inputSeconds=300 displayMeters=420 displaySeconds=300"))
        for(secret in listOf("127.123456","37.123456","private-stop","private-route","private-service","Authorization"))
            assertFalse(lines.joinToString().contains(secret))
        record(AutoGuidanceStatus.RELAXED,enabled=false);assertEquals(2,lines.size)
    }
}
