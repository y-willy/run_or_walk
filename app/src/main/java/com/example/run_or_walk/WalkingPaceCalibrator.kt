package com.example.run_or_walk

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Uses only fresh, accurate pedestrian fixes; an uncertain sample never overrides a preset. */
internal class WalkingPaceCalibrator {
    private val samples = ArrayDeque<GuidanceLocation>()
    private var smoothedMetersPerSecond: Double? = null
    private var lastAcceptedEpochMillis = -1L

    fun observe(location: GuidanceLocation?, nowEpochMillis: Long): Double? {
        if (location == null || nowEpochMillis - location.observedAtEpochMillis !in 0L..10_000L ||
            location.observedAtEpochMillis <= 0L) return current(nowEpochMillis)
        if (samples.lastOrNull()?.observedAtEpochMillis == location.observedAtEpochMillis) return current(nowEpochMillis)
        if (samples.lastOrNull()?.observedAtEpochMillis?.let { it > location.observedAtEpochMillis } == true) return current(nowEpochMillis)
        val speed = location.speedMetersPerSecond
        val validSensor = location.accuracyMeters in 0.0..25.0 &&
            speed != null && speed.isFinite() && speed in 0.65..2.4 &&
            location.speedAccuracyMetersPerSecond?.let { it.isFinite() && it in 0.0..0.75 } == true
        if (!validSensor) {
            if (speed != null && speed.isFinite() && speed < 0.45) {
                samples.clear() // Do not use a walking velocity after stopping.
                smoothedMetersPerSecond = null
            }
            return current(nowEpochMillis)
        }
        val measuredSpeed = speed ?: return current(nowEpochMillis)
        samples.addLast(location)
        while (samples.firstOrNull()?.let { location.observedAtEpochMillis - it.observedAtEpochMillis > 30_000L } == true)
            samples.removeFirst()
        val first = samples.firstOrNull() ?: return current(nowEpochMillis)
        val elapsed = location.observedAtEpochMillis - first.observedAtEpochMillis
        if (samples.size < 2 || elapsed !in 8_000L..30_000L) return current(nowEpochMillis)
        val displacementSpeed = paceDistanceMeters(first.point, location.point) / (elapsed / 1_000.0)
        if (displacementSpeed !in 0.45..2.7 || abs(displacementSpeed - measuredSpeed) > 1.0) return current(nowEpochMillis)
        val recent = samples.mapNotNull(GuidanceLocation::speedMetersPerSecond)
        val averaged = recent.average().coerceIn(0.65, 2.4)
        smoothedMetersPerSecond = smoothedMetersPerSecond?.let { it * 0.6 + averaged * 0.4 } ?: averaged
        lastAcceptedEpochMillis = location.observedAtEpochMillis
        return current(nowEpochMillis)
    }

    fun current(nowEpochMillis: Long): Double? = smoothedMetersPerSecond?.takeIf {
        nowEpochMillis - lastAcceptedEpochMillis in 0L..30_000L
    }
}

private fun paceDistanceMeters(a: MapPoint, b: MapPoint): Double {
    val lat = Math.toRadians(b.y - a.y)
    val lon = Math.toRadians(b.x - a.x)
    val h = sin(lat / 2).let { it * it } + cos(Math.toRadians(a.y)) *
        cos(Math.toRadians(b.y)) * sin(lon / 2).let { it * it }
    return 6_371_000.0 * 2.0 * asin(sqrt(h.coerceIn(0.0, 1.0)))
}
