package com.example.run_or_walk

internal enum class MovementAdvice { RELAXED, WALK, CAUTION, RUN, WAIT_NEXT, UNKNOWN }
internal fun AutoGuidanceAssessment.movementAdvice() = when(status) {
    AutoGuidanceStatus.RELAXED -> MovementAdvice.RELAXED
    AutoGuidanceStatus.WALK -> MovementAdvice.WALK
    AutoGuidanceStatus.CAUTION -> MovementAdvice.CAUTION
    AutoGuidanceStatus.FAST_WALK -> MovementAdvice.RUN
    AutoGuidanceStatus.NEXT_VEHICLE -> MovementAdvice.WAIT_NEXT
    AutoGuidanceStatus.UNKNOWN -> MovementAdvice.UNKNOWN
}

/** Existing source/GPS/identity checks; the user's five slack bands are the only status rule. */
internal fun calculateMovementGuidance(input: AutoGuidanceInput,now: Long,settings: JourneySettings): AutoGuidanceAssessment {
    val checked=calculateAutoGuidance(input.copy(previousAssessment=null,bufferSeconds=settings.bufferSeconds),now,
        AutoGuidancePolicy(fastSpeedKmh=settings.measuredRunningSpeedKmh ?: 6.0))
    if(checked.status==AutoGuidanceStatus.UNKNOWN) return checked
    val runSeconds=settings.measuredRunningSpeedKmh?.let { speed -> input.walkingRoute?.distanceMeters?.let {
        kotlin.math.ceil(it*3.6/speed).toInt() } }
    return checked.copy(fastTravelSeconds=runSeconds)
}

internal data class MovementTransition(val assessment: AutoGuidanceAssessment,val vibrationCount: Int)
internal fun movementPulseCount(status: AutoGuidanceStatus):Int = when(status) {
    AutoGuidanceStatus.RELAXED,AutoGuidanceStatus.WALK -> 1
    AutoGuidanceStatus.CAUTION,AutoGuidanceStatus.FAST_WALK -> 2
    AutoGuidanceStatus.NEXT_VEHICLE -> 3
    AutoGuidanceStatus.UNKNOWN -> 0
}

/** UI changes immediately. Only haptics require the changed state twice, >=3 seconds apart.
 * Suppressed/disabled events are consumed, never queued. Actual pulses use the process gate.
 */
internal class MovementAdviceTransitions {
    private var identity:OfficialBoardingIdentity?=null
    private var established:AutoGuidanceStatus?=null
    private var candidate:AutoGuidanceStatus?=null
    private var candidateAt=0L
    fun observe(raw: AutoGuidanceAssessment,now:Long,vibrationEnabled:Boolean):MovementTransition {
        if(raw.boardingIdentity!=null && raw.boardingIdentity!=identity) {
            identity=raw.boardingIdentity;established=null;candidate=null
        }
        if(raw.status==AutoGuidanceStatus.UNKNOWN) {candidate=null;return MovementTransition(raw,0)}
        val previous=established
        if(previous==null) {established=raw.status;return MovementTransition(raw,0)}
        if(previous==raw.status) {candidate=null;return MovementTransition(raw,0)}
        if(candidate!=raw.status || now<candidateAt) {candidate=raw.status;candidateAt=now;return MovementTransition(raw,0)}
        if(now-candidateAt<3000L) return MovementTransition(raw,0)
        established=raw.status;candidate=null
        return MovementTransition(raw,if(vibrationEnabled) movementPulseCount(raw.status) else 0)
    }
}

/** Shared by screen, service and manual vibration paths. Record only a successful emission. */
internal class ActualVibrationGate {
    private var lastActualElapsed:Long?=null
    @Synchronized fun emit(nowElapsed:Long,action:()->Boolean):Boolean {
        if(lastActualElapsed?.let { nowElapsed-it<30000L || nowElapsed<it }==true) return false
        if(!action()) return false
        lastActualElapsed=nowElapsed
        return true
    }
}
internal object ProcessVibrationGate {val gate=ActualVibrationGate()}
