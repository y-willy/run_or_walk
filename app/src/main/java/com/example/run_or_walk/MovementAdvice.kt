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

internal enum class MovementVibration(val description: String) {
    NONE("진동 없음"), ONE_PULSE("1회"), TWO_PULSES("2회"), THREE_PULSES("3회"), LONG_TWO_SECONDS("2초 연속")
}
internal data class MovementTransition(val assessment: AutoGuidanceAssessment,val vibration: MovementVibration)
internal fun movementVibration(status: AutoGuidanceStatus): MovementVibration = when(status) {
    AutoGuidanceStatus.RELAXED,AutoGuidanceStatus.UNKNOWN -> MovementVibration.NONE
    AutoGuidanceStatus.WALK -> MovementVibration.ONE_PULSE
    AutoGuidanceStatus.CAUTION -> MovementVibration.TWO_PULSES
    AutoGuidanceStatus.FAST_WALK -> MovementVibration.THREE_PULSES
    AutoGuidanceStatus.NEXT_VEHICLE -> MovementVibration.LONG_TWO_SECONDS
}

internal fun movementVibrationPattern(vibration: MovementVibration): LongArray = when(vibration) {
    MovementVibration.NONE -> longArrayOf()
    MovementVibration.ONE_PULSE -> longArrayOf(0,100)
    MovementVibration.TWO_PULSES -> longArrayOf(0,100,150,100)
    MovementVibration.THREE_PULSES -> longArrayOf(0,100,150,100,150,100)
    MovementVibration.LONG_TWO_SECONDS -> longArrayOf(0,2000)
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
        if(raw.status==AutoGuidanceStatus.UNKNOWN) {candidate=null;return MovementTransition(raw,MovementVibration.NONE)}
        val previous=established
        if(previous==null) {established=raw.status;return MovementTransition(raw,MovementVibration.NONE)}
        if(previous==raw.status) {candidate=null;return MovementTransition(raw,MovementVibration.NONE)}
        if(candidate!=raw.status || now<candidateAt) {candidate=raw.status;candidateAt=now;return MovementTransition(raw,MovementVibration.NONE)}
        if(now-candidateAt<3000L) return MovementTransition(raw,MovementVibration.NONE)
        established=raw.status;candidate=null
        return MovementTransition(raw,if(vibrationEnabled) movementVibration(raw.status) else MovementVibration.NONE)
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
