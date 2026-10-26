package com.example.run_or_walk

internal enum class MovementAdvice { WALK, RUN, WAIT_NEXT, UNKNOWN }
internal fun AutoGuidanceAssessment.movementAdvice() = when(status) {
    AutoGuidanceStatus.WALK -> MovementAdvice.WALK
    AutoGuidanceStatus.FAST_WALK -> MovementAdvice.RUN
    AutoGuidanceStatus.NEXT_VEHICLE -> MovementAdvice.WAIT_NEXT
    AutoGuidanceStatus.UNKNOWN -> MovementAdvice.UNKNOWN
}

/** Reuses all existing source, identity and location checks. Running is never a preset brisk pace. */
internal fun calculateMovementGuidance(input: AutoGuidanceInput,now: Long,settings: JourneySettings): AutoGuidanceAssessment {
    val runningSpeed = settings.measuredRunningSpeedKmh
    val checked = calculateAutoGuidance(input.copy(previousAssessment=null,bufferSeconds=settings.bufferSeconds),now,
        AutoGuidancePolicy(fastSpeedKmh=runningSpeed ?: 6.0))
    if (checked.status == AutoGuidanceStatus.UNKNOWN) return checked
    val first = checked.firstArrivalAtEpochMillis ?: return checked.copy(status=AutoGuidanceStatus.UNKNOWN)
    val walk = checked.walkingSeconds ?: return checked.copy(status=AutoGuidanceStatus.UNKNOWN)
    val availableMillis = first-now-settings.bufferSeconds*1000L
    val runSeconds=runningSpeed?.let { speed -> input.walkingRoute?.distanceMeters?.let { kotlin.math.ceil(it*3.6/speed).toInt() } }
    if (availableMillis >= walk*1000L) return checked.copy(status=AutoGuidanceStatus.WALK,nextVehicleState=null,fastTravelSeconds=runSeconds)
    if (runningSpeed == null) return checked.copy(status=AutoGuidanceStatus.UNKNOWN,
        unknownReason=AutoGuidanceUnknownReason.RUNNING_SPEED_UNAVAILABLE,fastTravelSeconds=null,nextVehicleState=null)
    if(runSeconds == null) return checked.copy(status=AutoGuidanceStatus.UNKNOWN)
    return checked.copy(status=if (availableMillis >= runSeconds*1000L) AutoGuidanceStatus.FAST_WALK
        else AutoGuidanceStatus.NEXT_VEHICLE,fastTravelSeconds=runSeconds)
}

internal data class MovementTransition(val assessment: AutoGuidanceAssessment,val vibrationCount: Int)

/** UI updates after the release margin; only haptics wait for two observations >=3 s apart.
 * Within 30 s, a newly higher urgency may alert once; repeated same-level oscillations stay silent.
 */
internal class MovementAdviceTransitions {
    private var identity: OfficialBoardingIdentity? = null
    private var established: AutoGuidanceStatus? = null
    private var candidate: AutoGuidanceStatus? = null
    private var candidateAt = 0L
    private var lastVibration: Long? = null
    private var lastVibrationLevel = 0
    fun observe(raw: AutoGuidanceAssessment,now: Long,vibrationEnabled: Boolean): MovementTransition {
        if (raw.boardingIdentity != null && raw.boardingIdentity != identity) {
            identity=raw.boardingIdentity; established=null; candidate=null
            lastVibration=null; lastVibrationLevel=0
        }
        if (raw.status == AutoGuidanceStatus.UNKNOWN) { candidate=null; return MovementTransition(raw,0) }
        val previous = established
        if (previous == null) { established=raw.status; return MovementTransition(raw,0) }
        if (raw.status == previous) { candidate=null; return MovementTransition(raw,0) }
        val available = (raw.firstArrivalAtEpochMillis ?: now)-now-raw.bufferSeconds*1000L
        val relaxToWalk = raw.status == AutoGuidanceStatus.WALK && available-(raw.walkingSeconds ?: 0)*1000L < 10_000L
        val relaxToRun = previous == AutoGuidanceStatus.NEXT_VEHICLE && raw.status == AutoGuidanceStatus.FAST_WALK &&
            available-(raw.fastTravelSeconds ?: 0)*1000L < 10_000L
        if (relaxToWalk || relaxToRun) { candidate=null; return MovementTransition(raw.copy(status=previous),0) }
        if (candidate != raw.status || now < candidateAt) {
            candidate=raw.status; candidateAt=now; return MovementTransition(raw,0)
        }
        if (now-candidateAt < 3_000L) return MovementTransition(raw,0)
        established=raw.status; candidate=null
        val level = urgencyLevel(raw.status)
        val inCooldown = lastVibration?.let { now-it < 30_000L } == true
        val newEscalation = level > urgencyLevel(previous) && level > lastVibrationLevel
        val pulse = if (vibrationEnabled && (!inCooldown || newEscalation)) level else 0
        if (pulse > 0) { lastVibration=now; lastVibrationLevel=level }
        return MovementTransition(raw,pulse)
    }
}

private fun urgencyLevel(status: AutoGuidanceStatus): Int = when(status) {
    AutoGuidanceStatus.WALK -> 1
    AutoGuidanceStatus.FAST_WALK -> 2
    AutoGuidanceStatus.NEXT_VEHICLE -> 3
    AutoGuidanceStatus.UNKNOWN -> 0
}
