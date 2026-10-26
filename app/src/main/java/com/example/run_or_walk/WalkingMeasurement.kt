package com.example.run_or_walk

import kotlin.math.*

internal enum class StepSensorMode { COUNTER, DETECTOR, UNAVAILABLE, PERMISSION_DENIED }
internal enum class MeasurementPhase { READY, RECORDING, PAUSED, FINISHED }
internal data class WalkingMeasurementResult(
    val distanceMeters: Double = 0.0,
    val steps: Int? = null,
    val pairedDistanceMeters: Double = 0.0,
    val pairedSteps: Int = 0,
    val movingSeconds: Double = 0.0,
    val activeSeconds: Double = 0.0,
    val sensorMode: StepSensorMode = StepSensorMode.UNAVAILABLE,
    val sensorReset: Boolean = false,
    val savedAtEpochMillis: Long = 0L
) {
    val speedKmh: Double? get() = (distanceMeters / movingSeconds * 3.6)
        .takeIf { movingSeconds > 0 && it.isFinite() && it in 2.0..7.0 }
    val reliableSpeed: Boolean get() = distanceMeters >= 100 && movingSeconds >= 60 && speedKmh != null
    val strideCentimeters: Double? get() = (pairedDistanceMeters / pairedSteps * 100)
        .takeIf { pairedSteps >= 100 && pairedDistanceMeters >= 60 && it.isFinite() && it in 25.0..150.0 }
    val secondsPerKm: Int? get() = speedKmh?.let { (3600 / it).roundToInt() }
}

/** Counts only observed steps and plausible moving GPS segments, never elapsed-time distance. */
internal class WalkingMeasurementSession {
    var phase = MeasurementPhase.READY; private set
    private var mode = StepSensorMode.UNAVAILABLE
    private var counter: Long? = null
    private var steps = 0
    private var pairedSteps = 0
    private var distance = 0.0
    private var pairedDistance = 0.0
    private var movingSeconds = 0.0
    private var activeMillis = 0L
    private var startedAt: Long? = null
    private var anchor: GuidanceLocation? = null
    private var anchorSteps = 0
    private var reset = false

    fun start(sensorMode: StepSensorMode, elapsedMillis: Long) {
        if (phase == MeasurementPhase.RECORDING || phase == MeasurementPhase.FINISHED) return
        mode = sensorMode; phase = MeasurementPhase.RECORDING; startedAt = elapsedMillis
        anchor = null; counter = null; anchorSteps = steps
    }
    fun pause(elapsedMillis: Long) {
        if (phase != MeasurementPhase.RECORDING) return
        activeMillis += (elapsedMillis - (startedAt ?: elapsedMillis)).coerceAtLeast(0)
        startedAt = null; anchor = null; counter = null; phase = MeasurementPhase.PAUSED
    }
    fun finish(elapsedMillis: Long) { pause(elapsedMillis); phase = MeasurementPhase.FINISHED }
    fun sensorUnavailable() { mode = StepSensorMode.UNAVAILABLE; counter = null; anchor = null }
    fun observeCounter(value: Long) {
        if (phase != MeasurementPhase.RECORDING || mode != StepSensorMode.COUNTER || value < 0) return
        val old = counter; counter = value
        if (old == null) return
        if (value < old || value - old > 100) { reset = true; anchor = null; return }
        steps += (value - old).toInt()
    }
    fun observeStep() { if (phase == MeasurementPhase.RECORDING && mode == StepSensorMode.DETECTOR) steps++ }
    fun observeLocation(fix: GuidanceLocation, now: Long) {
        if (phase != MeasurementPhase.RECORDING) return
        if (!validPedestrianFix(fix,now)) { anchor = null; return }
        val previous = anchor
        if (previous != null && fix.observedAtEpochMillis <= previous.observedAtEpochMillis) return
        fun rebase() { anchor=fix; anchorSteps=steps }
        if (previous == null) { rebase(); return }
        val seconds = (fix.observedAtEpochMillis - previous.observedAtEpochMillis) / 1000.0
        if (seconds > 15.0) { rebase(); return }
        val stepDelta = steps-anchorSteps
        val hasSensor = mode in setOf(StepSensorMode.COUNTER,StepSensorMode.DETECTOR)
        if (fix.speedMetersPerSecond?.let { it < 0.45 || it > 2.4 } == true || hasSensor && stepDelta <= 0) {
            rebase(); return
        }
        // Compare longer displacement windows instead of summing every noisy 2–4 s GPS segment.
        if (seconds < 8.0) return
        val meters = pedestrianDistance(previous.point,fix.point)
        if (meters < max(6.0,max(previous.accuracyMeters,fix.accuracyMeters))) return
        val speed = meters / seconds
        if (speed !in 0.5..2.4 || hasSensor && (stepDelta/seconds !in 0.5..3.0 || meters/stepDelta !in 0.25..1.5)) {
            rebase(); return
        }
        if (!hasSensor && (fix.speedMetersPerSecond == null ||
            fix.speedAccuracyMetersPerSecond?.let { it <= 0.75 } != true)) { rebase(); return }
        distance += meters; movingSeconds += seconds
        if (hasSensor) { pairedSteps += stepDelta; pairedDistance += meters }
        rebase()
    }
    fun result(elapsedMillis: Long) = WalkingMeasurementResult(distance,
        steps.takeIf { mode in setOf(StepSensorMode.COUNTER,StepSensorMode.DETECTOR) },pairedDistance,pairedSteps,
        movingSeconds,(activeMillis + startedAt?.let { (elapsedMillis-it).coerceAtLeast(0) }.orZero())/1000.0,mode,reset)
}
private fun Long?.orZero() = this ?: 0L
internal fun validPedestrianFix(fix: GuidanceLocation,now: Long): Boolean =
    fix.point.x.isFinite() && fix.point.y.isFinite() && fix.point.x in -180.0..180.0 && fix.point.y in -90.0..90.0 &&
        fix.accuracyMeters.isFinite() && fix.accuracyMeters in 0.0..20.0 &&
        fix.observedAtEpochMillis > 0 && now-fix.observedAtEpochMillis in 0..10_000L
internal fun pedestrianDistance(a: MapPoint,b: MapPoint): Double {
    val lat = Math.toRadians(b.y-a.y); val lon = Math.toRadians(b.x-a.x)
    val h = sin(lat/2).pow(2)+cos(Math.toRadians(a.y))*cos(Math.toRadians(b.y))*sin(lon/2).pow(2)
    return 6_371_000*2*asin(sqrt(h.coerceIn(0.0,1.0)))
}
