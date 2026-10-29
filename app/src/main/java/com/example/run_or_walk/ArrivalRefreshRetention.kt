package com.example.run_or_walk

/** Merge one provider response without treating an omitted slot as a new observation.
 * Empty successful responses retire the whole selection. An occupied slot or an explicit
 * invalid/ended/passed row replaces its old occupant; all retained rows keep original dates.
 */
private fun <T> mergeArrivalSlots(previous: List<T>,incoming: List<T>,
    key:(T)->String?,slot:(T)->Int?,usable:(T)->Boolean,sourceAt:(T)->Long,
    incomplete:(T)->Boolean,sameObservation:(T,T)->Boolean): List<T> {
    if(incoming.isEmpty()) return emptyList()
    val old=previous.filter(usable)
    val resolutions=incoming.map { fresh ->
        val prior=previous.firstOrNull { value ->
            (key(fresh)?.let { it==key(value) } ?: (slot(fresh)!=null && slot(fresh)==slot(value))) &&
                (usable(value) || sameObservation(fresh,value))
        }
        val retained=prior!=null && (sameObservation(fresh,prior) || sourceAt(fresh)<sourceAt(prior) || incomplete(fresh))
        Triple(fresh,if(retained) requireNotNull(prior) else fresh,!retained)
    }
    val updated=resolutions.map { it.second }.distinctBy { key(it) ?: "slot:${slot(it)}" }
    val keys=incoming.mapNotNull(key).toSet()
    val occupied=resolutions.filter { it.third }.mapNotNull { slot(it.first) }.toSet()
    return updated+old.filter { value ->
        key(value)?.let { it !in keys } ?: true
    }.filter { value -> slot(value)?.let { it !in occupied } ?: (incoming.size<2) }
        .filter { value -> updated.none { candidate -> key(value)!=null && key(value)==key(candidate) } }
}

internal fun mergeSeoulBusArrivals(identity: OfficialBoardingIdentity,previous: List<OfficialSeoulBusArrival>,
    incoming: List<OfficialSeoulBusArrival>,now: Long): List<OfficialSeoulBusArrival> = mergeArrivalSlots(
    previous,incoming,
    key={ it.vehicleId.takeIf(String::isNotBlank) ?: it.officialSlot?.let { slot -> "slot:$slot" }
        ?: it.snapshot.vehicleIdentityFallback },slot={it.officialSlot},
    usable={it.identity==identity && it.isUsableBusArrival(now)},sourceAt={it.snapshot.fetchedAtEpochMillis},
    incomplete={it.identity==identity && it.sourceEtaSeconds==null &&
        it.snapshot.trustState==ArrivalTrustState.INVALID_ETA && it.isFull!=true &&
        !hasUncertainSeoulBusServiceState(it.officialArrivalMessage,it.issues)},
    sameObservation={a,b -> a.identity==b.identity && a.vehicleId==b.vehicleId && a.officialSlot==b.officialSlot &&
        a.gatewayFetchedAtEpochMillis!=null && a.gatewayFetchedAtEpochMillis==b.gatewayFetchedAtEpochMillis &&
        a.sourceEtaSeconds!=null && a.sourceEtaSeconds==b.sourceEtaSeconds &&
        a.isFull==b.isFull && a.isLast==b.isLast && a.issues==b.issues &&
        a.officialArrivalMessage==b.officialArrivalMessage && a.snapshot.trustState==b.snapshot.trustState})

internal fun mergeGyeonggiBusArrivals(match: GyeonggiOfficialMatch,previous: List<GyeonggiBusArrivalDisplay>,
    incoming: List<GyeonggiBusArrivalDisplay>,now: Long): List<GyeonggiBusArrivalDisplay> {
    val scoped=incoming.filter { it.stationId==match.stationId && it.routeId==match.routeId }
    val merged=mergeArrivalSlots(previous.filter { it.matchesOfficialMatch(match) },scoped,
        key={it.vehicleId.takeIf(String::isNotBlank) ?: "slot:${it.sequence}"},slot={it.sequence},
        usable={it.matchesOfficialMatch(match) && isUsableGyeonggiArrival(it,now)},
        sourceAt={it.arrivalBasisMillis(now)},
        incomplete={it.matchesOfficialMatch(match) && it.seconds==null && it.trust==ArrivalTrustState.INVALID_ETA},
        sameObservation={a,b -> a.stationId==b.stationId && a.routeId==b.routeId && a.staOrder==b.staOrder &&
            a.vehicleId==b.vehicleId && a.sequence==b.sequence && a.observedAtMillis==b.observedAtMillis &&
            a.fetchedAtMillis==b.fetchedAtMillis && a.seconds==b.seconds && a.trust==b.trust && a.serviceEnded==b.serviceEnded})
    return merged+incoming.filter { it.stationId!=match.stationId || it.routeId!=match.routeId }
}

/** Loading never overrides data; an absence is only authoritative while its response is fresh. */
internal fun missingArrivalText(responseAt: Long?,hasObservations: Boolean,failed: Boolean,
    noService: Boolean,now: Long): String = when {
    failed -> "도착정보를 불러오지 못했어요"
    responseAt!=null && now-responseAt in 0L..60_000L && noService -> "운행정보 없음"
    hasObservations || responseAt!=null -> "최신 도착정보 확인 필요"
    else -> "도착정보 확인 중"
}

/** Actionable missing-data wording; periodic refresh itself is never a reason to replace valid guidance. */
internal fun unknownGuidanceTitle(reason:AutoGuidanceUnknownReason?,fallback:String):String=when(reason) {
    AutoGuidanceUnknownReason.BOARDING_UNCONFIRMED -> "승차 장소·방향을 확인해 주세요"
    AutoGuidanceUnknownReason.LOCATION_PERMISSION_REQUIRED -> "위치 권한을 허용해 주세요"
    AutoGuidanceUnknownReason.LOCATION_UNAVAILABLE,AutoGuidanceUnknownReason.LOCATION_INACCURATE,AutoGuidanceUnknownReason.LOCATION_STALE -> "GPS 위치를 다시 확인해 주세요"
    AutoGuidanceUnknownReason.WALKING_STALE -> "현재 위치 기준으로 다시 계산해요"
    AutoGuidanceUnknownReason.WALKING_UNAVAILABLE,AutoGuidanceUnknownReason.WALKING_MISMATCH -> "승차점까지 도보 경로를 확인해 주세요"
    AutoGuidanceUnknownReason.ARRIVAL_MISMATCH -> "탑승 방향을 다시 선택해 주세요"
    AutoGuidanceUnknownReason.ARRIVAL_STALE -> "새로운 차량 도착정보를 기다리고 있어요"
    AutoGuidanceUnknownReason.ARRIVAL_UNCERTAIN -> "차량 도착시간을 다시 확인해요"
    AutoGuidanceUnknownReason.ARRIVAL_INVALID_ETA,AutoGuidanceUnknownReason.ARRIVAL_FUTURE_TIMESTAMP -> "도착시간을 다시 확인해요"
    else -> if(fallback=="도착정보 확인 중") "첫 도착정보를 연결하고 있어요" else fallback
}
