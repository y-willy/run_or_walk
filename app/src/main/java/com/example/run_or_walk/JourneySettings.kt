package com.example.run_or_walk

import org.json.JSONObject
import kotlin.math.ceil

/** User choices change walking estimates, never the provider's vehicle arrival time. */
data class JourneySettings(
    val useProviderWalkingTime: Boolean = false,
    val speedKmh: Double = 4.68,
    val bufferSeconds: Int = 120,
    val gpsAutoCalibrationEnabled: Boolean = true
) {
    init {
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
        val calibrated = calibratedSpeedMetersPerSecond?.takeIf {
            gpsAutoCalibrationEnabled && it.isFinite() && it in 0.65..2.4
        }
        if ((useProviderWalkingTime && calibrated == null) || distanceMeters == 0) return providerDurationSeconds
        return ceil(distanceMeters.toDouble() * 3.6 / (calibrated?.times(3.6) ?: speedKmh))
            .coerceAtMost(Int.MAX_VALUE.toDouble()).toInt()
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
    private const val VERSION = 2
    fun encode(settings: JourneySettings): String = JSONObject()
        .put("version", VERSION)
        .put("useProviderWalkingTime", settings.useProviderWalkingTime)
        .put("speedKmh", settings.speedKmh)
        .put("bufferSeconds", settings.bufferSeconds)
        .put("gpsAutoCalibrationEnabled", settings.gpsAutoCalibrationEnabled)
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
        val auto = if (version == VERSION)
            (root.opt("gpsAutoCalibrationEnabled") as? Boolean) ?: defaults.gpsAutoCalibrationEnabled
        else defaults.gpsAutoCalibrationEnabled
        return JourneySettings(mode, speed, buffer, auto)
    }
}
