package com.example.run_or_walk

/** Whitelisted debug diagnostics. Never serialize inputs: they contain GPS/stop coordinates. */
internal class GuidanceStateTrace(private val source:String,
    private val sink:(String)->Unit={android.util.Log.d("RunOrWalkGuidance",it)}) {
    private var previous:AutoGuidanceStatus?=null
    private var fingerprint:String?=null
    fun record(assessment:AutoGuidanceAssessment,location:GuidanceLocation?,walking:GuidanceWalkingRoute?,
        arrivals:RealtimeArrivalSnapshot?,boarding:GuidanceBoarding?,display:WalkRoute?,
        revision:Long,request:Long,calculatedAt:Long?,now:Long,enabled:Boolean=BuildConfig.DEBUG) {
        if(!enabled) return
        val identityMatches=boarding?.identity?.let {id -> walking?.boardingIdentity==id &&
            (arrivals==null || arrivals.identity==id)}==true
        val key=listOf(assessment.status,assessment.unknownReason,walking?.fetchedAtEpochMillis,
            walking?.distanceMeters,walking?.durationSeconds,display,arrivals?.fetchedAtEpochMillis,
            arrivals?.firstArrivalAtEpochMillis,arrivals?.nextArrivalAtEpochMillis,identityMatches,revision,request).joinToString("|")
        if(key==fingerprint) return
        val action=if(assessment.status==AutoGuidanceStatus.UNKNOWN) "unusable:${assessment.unknownReason}" else "usable"
        sink("source=$source previous=$previous current=${assessment.status} reason=${assessment.unknownReason} " +
            "gpsAt=${location?.observedAtEpochMillis} accuracyM=${location?.accuracyMeters} " +
            "walkAt=${walking?.fetchedAtEpochMillis} walkUntil=${walking?.let {it.fetchedAtEpochMillis+minOf(it.validForMillis,120000)}} " +
            "calculatedAt=$calculatedAt inputMeters=${walking?.distanceMeters} inputSeconds=${walking?.durationSeconds} " +
            "displayMeters=${display?.distanceMeters} displaySeconds=${display?.durationSeconds} " +
            "firstAt=${arrivals?.firstArrivalAtEpochMillis} firstUntil=${arrivals?.arrivalValidityDeadline()} " +
            "nextAt=${arrivals?.nextArrivalAtEpochMillis} nextUntil=${arrivals?.let {it.nextArrivalValidUntilEpochMillis ?: it.arrivalValidityDeadline()}} " +
            "boardingConfirmed=${boarding?.confirmed} identityMatches=$identityMatches " +
            "selection=$revision request=$request now=$now action=$action")
        fingerprint=key;previous=assessment.status
    }
}
