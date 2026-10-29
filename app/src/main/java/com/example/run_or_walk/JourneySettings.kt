package com.example.run_or_walk

import org.json.JSONObject
import kotlin.math.ceil

/** User choices change walking estimates, never the provider's vehicle arrival time. */
data class JourneySettings(
    val useProviderWalkingTime: Boolean = false,
    val speedKmh: Double = 4.68,
    val bufferSeconds: Int = 120,
    val gpsAutoCalibrationEnabled: Boolean = true,
    val personalWalkingSpeedKmh: Double? = null,
    val usePersonalWalkingSpeed: Boolean = false,
    val measuredRunningSpeedKmh: Double? = null
) {
    init {
        require(personalWalkingSpeedKmh == null || personalWalkingSpeedKmh.isFinite() && personalWalkingSpeedKmh in 2.0..7.0)
        require(measuredRunningSpeedKmh == null || measuredRunningSpeedKmh.isFinite() && measuredRunningSpeedKmh in 3.0..12.0)
        require(speedKmh.isFinite() && speedKmh in MIN_SPEED_KMH..MAX_SPEED_KMH &&
            supportedSpeedKmh(speedKmh)) { "Walking speed must be a supported pace" }
        require(bufferSeconds in MIN_BUFFER_SECONDS..MAX_BUFFER_SECONDS && bufferSeconds % 30 == 0) {
            "Safety buffer must be a supported 30-second value"
        }
    }

    /** Custom time uses the actual walking-route distance; crossings remain part of the buffer.
     * A missing/zero distance never erases a nonzero provider walking duration.
     */
    fun walkingSeconds(distanceMeters: Int, providerDurationSeconds: Int,
        calibratedSpeedMetersPerSecond: Double? = null): Int {
        require(distanceMeters >= 0 && providerDurationSeconds >= 0) { "Walking route values must not be negative" }
        val personal = personalWalkingSpeedKmh?.takeIf { usePersonalWalkingSpeed }
        val calibrated = calibratedSpeedMetersPerSecond?.takeIf {
            gpsAutoCalibrationEnabled && it.isFinite() && it in 0.65..2.4
        }
        if ((useProviderWalkingTime && calibrated == null && personal == null) || distanceMeters == 0) return providerDurationSeconds
        return ceil(distanceMeters.toDouble() / walkingSpeedMetersPerSecond(calibrated))
            .coerceAtMost(Int.MAX_VALUE.toDouble()).toInt()
    }

    /** The chosen pace stays the baseline. GPS contributes 30%, capped to a 20% correction.
     * Zero/missing velocity is never the speed at which a future walk will be completed.
     * Callers validate the GPS observation separately with usableWalkingCalibration.
     */
    internal fun walkingSpeedMetersPerSecond(calibratedSpeedMetersPerSecond: Double? = null): Double {
        val baseline = (personalWalkingSpeedKmh?.takeIf { usePersonalWalkingSpeed } ?: speedKmh) / 3.6
        val measured = calibratedSpeedMetersPerSecond?.takeIf {
            gpsAutoCalibrationEnabled && it.isFinite() && it in 0.65..2.4
        } ?: return baseline
        return (baseline * 0.7 + measured * 0.3).coerceIn(baseline * 0.8, baseline * 1.2)
    }

    /** Explicit brisk speed, never inferred as a percentage of the provider's duration. */
    val fastSpeedKmh: Double
        get() = maxOf(6.0, speedKmh + 1.2)

    companion object {
        const val MIN_SPEED_KMH = 2.0
        const val MAX_SPEED_KMH = 7.0
        const val MIN_BUFFER_SECONDS = 30
        const val MAX_BUFFER_SECONDS = 600

        // 0.9 / 1.3 / 1.7 metres per second.
        val PRESET_SPEED_KMH = listOf(3.24, 4.68, 6.12)

        fun supportedSpeedKmh(value: Double): Boolean =
            PRESET_SPEED_KMH.any { kotlin.math.abs(it - value) < 0.001 } ||
                value * 2.0 % 1.0 == 0.0 // Earlier manually saved speeds remain valid.
    }
}

/** Small, versioned private preference payload. It contains no key, position or live ETA. */
internal object JourneySettingsCodec {
    private const val VERSION = 3
    fun encode(settings: JourneySettings): String = JSONObject()
        .put("version", VERSION)
        .put("useProviderWalkingTime", settings.useProviderWalkingTime)
        .put("speedKmh", settings.speedKmh)
        .put("bufferSeconds", settings.bufferSeconds)
        .put("gpsAutoCalibrationEnabled", settings.gpsAutoCalibrationEnabled)
        .put("personalWalkingSpeedKmh", settings.personalWalkingSpeedKmh)
        .put("usePersonalWalkingSpeed", settings.usePersonalWalkingSpeed)
        .put("measuredRunningSpeedKmh", settings.measuredRunningSpeedKmh)
        .toString()

    fun decode(payload: String?): JourneySettings {
        val defaults = JourneySettings()
        if (payload.isNullOrBlank() || payload.length > 4_096) return defaults
        val root = runCatching { JSONObject(payload) }.getOrNull() ?: return defaults
        val version = (root.opt("version") as? Number)?.toInt()
        if (version == null || version !in 1..VERSION) return defaults
        val mode = root.opt("useProviderWalkingTime") as? Boolean ?: defaults.useProviderWalkingTime
        val speed = (root.opt("speedKmh") as? Number)?.toDouble()?.takeIf {
            it.isFinite() && it in JourneySettings.MIN_SPEED_KMH..JourneySettings.MAX_SPEED_KMH &&
            JourneySettings.supportedSpeedKmh(it)
        } ?: defaults.speedKmh
        val buffer = (root.opt("bufferSeconds") as? Number)?.toDouble()?.takeIf {
            it.isFinite() && it % 1.0 == 0.0 &&
                it in JourneySettings.MIN_BUFFER_SECONDS.toDouble()..JourneySettings.MAX_BUFFER_SECONDS.toDouble() &&
                it % 30.0 == 0.0
        }?.toInt() ?: defaults.bufferSeconds
        val auto = if (version >= 2)
            (root.opt("gpsAutoCalibrationEnabled") as? Boolean) ?: defaults.gpsAutoCalibrationEnabled
        else defaults.gpsAutoCalibrationEnabled
        fun optionalSpeed(key: String,range: ClosedFloatingPointRange<Double>) = (root.opt(key) as? Number)?.toDouble()?.takeIf { it.isFinite() && it in range }
        return JourneySettings(mode, speed, buffer, auto,
            optionalSpeed("personalWalkingSpeedKmh",2.0..7.0),root.optBoolean("usePersonalWalkingSpeed",false),
            optionalSpeed("measuredRunningSpeedKmh",3.0..12.0))
    }
}
