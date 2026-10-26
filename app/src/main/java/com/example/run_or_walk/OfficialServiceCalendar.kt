package com.example.run_or_walk

import android.content.Context
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Published government calendar, including substitute holidays; no weekday guessing outside coverage. */
class OfficialServiceCalendar(context: Context) {
    private val raw by lazy {
        context.applicationContext.assets.open("official_service_calendar.json").bufferedReader().use { JSONObject(it.readText()) }
    }
    fun day(date: LocalDate): OfficialTimetableCalendarDay? {
        val dates = raw.getJSONObject("holidays").optJSONArray(date.year.toString()) ?: return null
        val holiday = (0 until dates.length()).any { dates.getString(it) == date.toString() }
        val type = when {
            holiday || date.dayOfWeek == DayOfWeek.SUNDAY -> OfficialServiceDayType.HOLIDAY
            date.dayOfWeek == DayOfWeek.SATURDAY -> OfficialServiceDayType.SATURDAY
            else -> OfficialServiceDayType.WEEKDAY
        }
        return OfficialTimetableCalendarDay(date, type, raw.getString("version"))
    }
    fun serviceDays(nowEpochMillis: Long): List<OfficialTimetableCalendarDay> {
        val local = Instant.ofEpochMilli(nowEpochMillis).atZone(ZoneId.of("Asia/Seoul"))
        return buildList {
            day(local.toLocalDate())?.let(::add)
            // Official 24:xx/25:xx trips belong to the preceding operating day.
            if (local.hour < 4) day(local.toLocalDate().minusDays(1))?.let(::add)
        }
    }
}
