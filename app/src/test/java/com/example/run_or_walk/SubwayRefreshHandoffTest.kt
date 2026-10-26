package com.example.run_or_walk

import org.junit.Assert.*
import org.junit.Test

class SubwayRefreshHandoffTest {
    private val now=1_800_000_000_000L
    private val id=OfficialBoardingIdentity("seoul","1075075241","1075","하행","고색행")
    private val original=OfficialSubwayArrival(id,"하행","고색행","6131",
        RealtimeArrivalSnapshot(id,now+240000,fetchedAtEpochMillis=now-20000,validForMillis=600000,
            receivedAtEpochMillis=now-20000),officialSlot=1,arrivalCode="99",sourceEtaSeconds=260)
    private fun analysis(rows: List<OfficialSubwayArrival> = listOf(original)) =
        SubwayTimetableAnalysis(rows,emptyList(),true,null,id.stopId,id.routeId)
    private fun position() = original.copy(snapshot=original.snapshot.copy(firstArrivalAtEpochMillis=now,
        fetchedAtEpochMillis=now,receivedAtEpochMillis=now,reliable=false,trustState=ArrivalTrustState.UNKNOWN),
        sourceEtaSeconds=0,officialArrivalMessage="[3]번째 전역 (상갈)",gatewayFetchedAtEpochMillis=now,
        sourceReceivedAtEpochMillis=now,gatewayContract=SubwayGatewayContract.RAW_FIELDS)
    private fun retained(latest: List<OfficialSubwayArrival>,at: Long=now,choice: SubwayArrivalChoice?=null,
        selected: OfficialBoardingIdentity=id,prior: SubwayTimetableAnalysis=analysis()) =
        retainedSubwayArrivalDuringRefresh(prior,latest,selected,choice,at)

    @Test fun positionOnlyUpdateKeepsTheSameTrainsOfficialEtaWithEveryOriginalTimestamp() {
        val result=retained(listOf(position()))!!
        assertEquals(original,result)
        assertEquals(now-20000,result.snapshot.fetchedAtEpochMillis)
        assertEquals(now-20000,result.snapshot.receivedAtEpochMillis!!)
        assertEquals(now+240000,result.snapshot.firstArrivalAtEpochMillis)
        assertEquals(original,retained(listOf(position()),choice=SubwayArrivalChoice.from(original)))
    }
    @Test fun originalReceiptExpiryStillRetiresCountdownEvenWhenPositionObservationIsFresh() {
        val freshPosition=position().copy(gatewayFetchedAtEpochMillis=now+45000,sourceReceivedAtEpochMillis=now+45000,
            snapshot=position().snapshot.copy(fetchedAtEpochMillis=now+45000,receivedAtEpochMillis=now+45000))
        assertNull(retained(listOf(freshPosition),at=now+45000))
    }
    @Test fun departedArrivedOrMissingTrainCannotKeepOldAdvice() {
        for(code in listOf("0","1","2")) assertNull(retained(listOf(position().copy(arrivalCode=code))))
        assertNull(retained(emptyList()))
        assertNull(retained(listOf(position().copy(trainId="different"))))
        assertNull(retained(listOf(position().copy(identity=id.copy(directionId="상행")))))
        assertNull(retained(listOf(position()),choice=SubwayArrivalChoice.from(original.copy(trainId="other"))))
    }
    @Test fun invalidStaleFutureOrGenericUnknownRowCannotKeepOldAdvice() {
        for(trust in listOf(ArrivalTrustState.INVALID_ETA,ArrivalTrustState.STALE,ArrivalTrustState.FUTURE_TIMESTAMP))
            assertNull(retained(listOf(position().copy(snapshot=position().snapshot.copy(trustState=trust)))))
        assertNull(retained(listOf(position().copy(sourceEtaSeconds=-1))))
        assertNull(retained(listOf(position().copy(officialArrivalMessage="확인 중"))))
        assertNull(retained(listOf(position().copy(sourceReceivedAtEpochMillis=now+1))))
        assertNull(retained(listOf(position().copy(snapshot=position().snapshot.copy(fetchedAtEpochMillis=now+1)))))
        assertNull(retained(listOf(position().copy(gatewayFetchedAtEpochMillis=now-61000,sourceReceivedAtEpochMillis=now-61000))))
        assertNull(retained(listOf(position().copy(issues=setOf(SubwayArrivalIssue.SOURCE_FUTURE)))))
    }
    @Test fun wrongStationDirectionOrServiceCannotReusePreviousAnalysis() {
        assertNull(retained(listOf(position()),selected=id.copy(stopId="wrong")))
        assertNull(retained(listOf(position()),selected=id.copy(routeId="1001")))
        assertNull(retained(listOf(position()),selected=id.copy(directionId="상행")))
        assertNull(retained(listOf(position()),selected=id.copy(serviceVariant="인천행")))
        assertNull(retained(listOf(position()),prior=analysis().copy(directionVerified=false)))
    }
    @Test fun duplicateOrAnonymousRowsDoNotPretendToBeTheSameTrain() {
        assertNull(retained(listOf(position(),position().copy(arrivalCode="2"))))
        val anonymous=original.copy(trainId="")
        assertNull(retained(listOf(position().copy(trainId="")),choice=SubwayArrivalChoice.from(anonymous),prior=analysis(listOf(anonymous))))
    }
    @Test fun newNumericEtaIsNotReplacedByThePreviousCountdown() {
        val numeric=position().copy(sourceEtaSeconds=180,snapshot=position().snapshot.copy(reliable=true,
            trustState=ArrivalTrustState.VALID,firstArrivalAtEpochMillis=now+180000))
        assertNull(retained(listOf(numeric)))
    }
    @Test fun retainedEtaStillRunsTheRealCalculatorRatherThanHoldingAnOldMovementDecision() {
        val row=retained(listOf(position()))!!
        val point=MapPoint(127.0,37.0)
        val input=AutoGuidanceInput(GuidanceLocation(point,3.0,now),GuidanceBoarding(id,point,true),
            GuidanceWalkingRoute(point,point,id,120,now-95000,120000,120),row.snapshot,true,
            bufferSeconds=60)
        val settings=JourneySettings(bufferSeconds=60)
        assertEquals(MovementAdvice.WALK,calculateMovementGuidance(input,now,settings).movementAdvice())
        assertEquals(MovementAdvice.UNKNOWN,calculateMovementGuidance(input.copy(
            walkingRoute=input.walkingRoute!!.copy(fetchedAtEpochMillis=now-121000)),now,settings).movementAdvice())
        assertEquals(MovementAdvice.UNKNOWN,calculateMovementGuidance(input.copy(
            location=input.location!!.copy(point=point.copy(y=37.001))),now,settings).movementAdvice())
    }
}
