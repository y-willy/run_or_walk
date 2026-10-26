package com.example.run_or_walk

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId

enum class OfficialServiceDayType { WEEKDAY, SATURDAY, HOLIDAY }

enum class OfficialTrainOperationType { LOCAL, EXPRESS, RAPID, OTHER }

/** Publication provenance and the service dates for which the publisher confirms this version. */
data class OfficialTimetableSource(
    val authority: String,
    val dataset: String,
    val version: String,
    val publishedAtEpochMillis: Long,
    val validFromServiceDate: LocalDate? = null,
    val validThroughServiceDate: LocalDate? = null,
    val timeZone: String = "Asia/Seoul",
    val scheduleAsOfDate: LocalDate? = null
)

/** An explicit official calendar entry; weekends and public holidays are never guessed. */
data class OfficialTimetableCalendarDay(
    val serviceDate: LocalDate,
    val dayType: OfficialServiceDayType,
    val sourceVersion: String
)

/** Only actual calls belong in this list: an express passing a station is not a stop. */
data class OfficialTimetableStop(
    val sequence: Int,
    val stopId: String,
    val stopName: String,
    val arrivalAtEpochMillis: Long?,
    val departureAtEpochMillis: Long?
)

data class OfficialTimetableTrip(
    val trainNo: String,
    val serviceDate: LocalDate,
    val dayType: OfficialServiceDayType,
    val routeId: String,
    val directionId: String,
    val serviceVariant: String,
    val operationType: OfficialTrainOperationType,
    /** A publisher-backed branch/stopping pattern identifier, never derived from train digits. */
    val routePatternId: String,
    val stops: List<OfficialTimetableStop>
)

data class OfficialTimetable(
    val source: OfficialTimetableSource,
    /** Namespace of routeId/stopId, independently of the publishing organisation. */
    val identityAuthority: String,
    val calendar: List<OfficialTimetableCalendarDay>,
    val trips: List<OfficialTimetableTrip>
)

/**
 * Converts publisher service-day times, including 24:xx and 25:xx, without changing serviceDate.
 * A plain 00:xx belongs to the beginning of the supplied service date, never the next date.
 */
fun officialTimetableTimeAt(
    serviceDate: LocalDate,
    time: String,
    timeZone: String = "Asia/Seoul"
): Long? = runCatching {
    require(Regex("[0-4][0-9]:[0-5][0-9]:[0-5][0-9]").matches(time))
    val parts = time.split(':').map(String::toInt)
    require(parts[0] <= 47)
    serviceDate.atStartOfDay(ZoneId.of(timeZone)).plusSeconds(
        parts[0] * 3_600L + parts[1] * 60L + parts[2]
    ).toInstant().toEpochMilli()
}.getOrNull()

/**
 * Normalized schema v1 only; this is deliberately not an adapter for an undocumented raw API.
 * Dates, exact IDs, calendar provenance and effective dates must be supplied by the official
 * adapter. Each stop accepts absolute arrival/departureAtEpochMillis and/or arrival/departureTime
 * (HH:mm:ss, hours 00..47); if both forms occur they must describe the same instant.
 * Malformed or conflicting data rejects the complete publication instead of silently dropping rows.
 */
fun parseOfficialTimetableJson(payload: String): OfficialTimetable? = runCatching {
    val root = JSONObject(payload)
    require(root.strictLong("schemaVersion") == 1L)
    val sourceJson = root.getJSONObject("source")
    val source = OfficialTimetableSource(
        authority = sourceJson.getString("authority"),
        dataset = sourceJson.getString("dataset"),
        version = sourceJson.getString("version"),
        publishedAtEpochMillis = sourceJson.strictLong("publishedAtEpochMillis"),
        validFromServiceDate = sourceJson.optionalDate("validFromServiceDate"),
        validThroughServiceDate = sourceJson.optionalDate("validThroughServiceDate"),
        timeZone = sourceJson.getString("timeZone"),
        scheduleAsOfDate = sourceJson.optionalDate("scheduleAsOfDate")
    )
    val calendar = root.getJSONArray("calendar").objects().map {
        OfficialTimetableCalendarDay(
            LocalDate.parse(it.getString("serviceDate")),
            OfficialServiceDayType.valueOf(it.getString("dayType")),
            it.getString("sourceVersion")
        )
    }
    val trips = root.getJSONArray("trips").objects().map { trip ->
        val date = LocalDate.parse(trip.getString("serviceDate"))
        OfficialTimetableTrip(
            trainNo = trip.getString("trainNo"),
            serviceDate = date,
            dayType = OfficialServiceDayType.valueOf(trip.getString("dayType")),
            routeId = trip.getString("routeId"),
            directionId = trip.getString("directionId"),
            serviceVariant = trip.getString("serviceVariant"),
            operationType = OfficialTrainOperationType.valueOf(trip.getString("operationType")),
            routePatternId = trip.getString("routePatternId"),
            stops = trip.getJSONArray("stops").objects().map { stop ->
                val sequence = stop.strictLong("sequence")
                require(sequence in 0..Int.MAX_VALUE.toLong())
                OfficialTimetableStop(sequence.toInt(), stop.getString("stopId"),
                    stop.getString("stopName"), stop.stopTime("arrival", date, source.timeZone),
                    stop.stopTime("departure", date, source.timeZone))
            }
        )
    }
    OfficialTimetable(source, root.getString("identityAuthority"), calendar, trips)
        .also { require(it.isStructurallyValid()) }
}.getOrNull()

private fun JSONObject.strictLong(name: String): Long {
    val value = get(name)
    require(value is Int || value is Long)
    return (value as Number).toLong()
}

private fun JSONObject.optionalDate(name: String): LocalDate? =
    if (!has(name) || isNull(name)) null else LocalDate.parse(getString(name))

private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map(::getJSONObject)

private fun JSONObject.stopTime(prefix: String, date: LocalDate, timeZone: String): Long? {
    val absoluteName = "${prefix}AtEpochMillis"
    val localName = "${prefix}Time"
    val absolute = if (has(absoluteName) && !isNull(absoluteName)) strictLong(absoluteName) else null
    val local = if (has(localName) && !isNull(localName)) {
        requireNotNull(officialTimetableTimeAt(date, getString(localName), timeZone))
    } else null
    require(absolute == null || local == null || absolute == local)
    return absolute ?: local
}

internal fun OfficialTimetable.isStructurallyValid(): Boolean = runCatching {
    require(listOf(source.authority, source.dataset, source.version, identityAuthority).all(::validTimetableText))
    require(source.publishedAtEpochMillis >= 0)
    val validFrom = source.validFromServiceDate
    val validThrough = source.validThroughServiceDate
    require(validFrom == null || validThrough == null || validFrom <= validThrough)
    // Korean official rail timetables are service dates in this timezone.
    require(source.timeZone == "Asia/Seoul")
    require(calendar.isNotEmpty() && trips.isNotEmpty())
    require(calendar.distinctBy { it.serviceDate }.size == calendar.size)
    require(calendar.all {
        (validFrom == null || it.serviceDate >= validFrom) &&
            (validThrough == null || it.serviceDate <= validThrough) &&
            validTimetableText(it.sourceVersion)
    })
    val days = calendar.associateBy { it.serviceDate }
    trips.forEach { trip ->
        require(listOf(trip.trainNo, trip.routeId, trip.directionId, trip.serviceVariant,
            trip.routePatternId).all(::validTimetableText))
        require(days[trip.serviceDate]?.dayType == trip.dayType)
        require(trip.directionId in setOf("상행", "하행", "내선", "외선"))
        require(trip.stops.size >= 2)
        val dayStart = requireNotNull(officialTimetableTimeAt(trip.serviceDate, "00:00:00", source.timeZone))
        val dayEnd = requireNotNull(officialTimetableTimeAt(trip.serviceDate.plusDays(2), "00:00:00", source.timeZone))
        var previousSequence = -1
        var previousTime: Long? = null
        trip.stops.forEach { stop ->
            require(stop.sequence > previousSequence)
            require(validTimetableText(stop.stopId) && validTimetableText(stop.stopName))
            require(stop.arrivalAtEpochMillis != null || stop.departureAtEpochMillis != null)
            val arrival = stop.arrivalAtEpochMillis
            val departure = stop.departureAtEpochMillis
            require(arrival == null || arrival in dayStart until dayEnd)
            require(departure == null || departure in dayStart until dayEnd)
            require(arrival == null || departure == null || arrival <= departure)
            val start = arrival ?: requireNotNull(departure)
            require(previousTime == null || start >= requireNotNull(previousTime))
            previousTime = departure ?: arrival
            previousSequence = stop.sequence
        }
    }
    true
}.getOrDefault(false)

internal fun validTimetableText(value: String): Boolean =
    value.isNotBlank() && value.length <= 512 && value == value.trim() && value.none(Char::isISOControl)
