package com.example.run_or_walk

import android.content.Context

/** Personal pace and safety preferences survive a process/app restart. */
class JourneySettingsStore(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences("journey_personal_settings", Context.MODE_PRIVATE)

    fun load(): JourneySettings = JourneySettingsCodec.decode(
        runCatching { preferences.getString("settings_v1", null) }.getOrNull())

    fun save(settings: JourneySettings) {
        preferences.edit().putString("settings_v1", JourneySettingsCodec.encode(settings)).apply()
    }
}
