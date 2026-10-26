package com.example.run_or_walk

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.time.LocalDate
import java.util.zip.GZIPInputStream

/** A direction supported by actual calls in a published train, including branch destinations. */
data class OfficialTimetableDirectionPath(
    val routeId: String,
    val directionId: String,
    val serviceVariant: String,
    val operationType: OfficialTrainOperationType,
    val boardingStopId: String,
    val targetStopId: String,
    val targetStopName: String,
    val nextStopIds: Set<String>,
    val matchingTripCount: Int,
    val source: OfficialTimetableSource
)

/**
 * Genuine official publications packaged for offline use. A line/day file is read once and
 * indexed by raw train number and station; only selected trips become absolute dated times.
 * Service day and calendar provenance must be supplied by a calendar adapter, never guessed here.
 * No provider key, network call, realtime ETA, or synthetic timetable is involved.
 */
class OfficialTimetableRepository private constructor(
    private val openAsset: (String) -> InputStream
) {
    constructor(context: Context) : this({ context.applicationContext.assets.open(it) })

    private val cacheMutex = Mutex()
    // A few active lines fit in memory; avoid retaining the complete metropolitan timetable.
    private val cache = object : LinkedHashMap<String, IndexedOfficialTimetable>(4, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, IndexedOfficialTimetable>?): Boolean =
            size > 3
    }
    private val groups: Set<String> by lazy {
        openAsset("timetables/manifest.json").bufferedReader(Charsets.UTF_8).use {
            val manifest = JSONObject(it.readText())
            require(manifest.getInt("schemaVersion") == 1)
            val array = manifest.getJSONArray("groups")
            (0 until array.length()).map { index ->
                val group = array.getJSONObject(index)
                "${group.getString("routeId")}_${group.getString("dayType")}"
            }.toSet()
        }
    }

    /**
     * With decorations disabled, matches the exact publisher train number only.
     * With decorations enabled, returns ALL raw numbers sharing the same numeric part. That is
     * candidate retrieval, not a cross-provider identity match: callers must prove uniqueness
     * using the line, direction, service, terminals and actual stop order before selecting one.
     */
    suspend fun timetableForTrain(
        routeId: String,
        trainNo: String,
        serviceDate: LocalDate,
        dayType: OfficialServiceDayType,
        calendarSourceVersion: String,
        includeDecoratedTrainNumbers: Boolean = false
    ): OfficialTimetable? = withContext(Dispatchers.IO) {
        if (!validTimetableText(trainNo) || !validTimetableText(calendarSourceVersion)) return@withContext null
        val data = load(routeId, dayType) ?: return@withContext null
        val indexes = if (includeDecoratedTrainNumbers) {
            val digits = officialTimetableTrainDigits(trainNo) ?: return@withContext null
            data.trainNumbers.asSequence().filter { officialTimetableTrainDigits(it) == digits }
                .flatMap { data.indexesForTrain(it).asSequence() }.distinct().toList()
        } else data.indexesForTrain(trainNo)
        data.forServiceDate(indexes, serviceDate, dayType, calendarSourceVersion)
    }

    /** Only trains with an actual call at this exact mapped station ID are returned. */
    suspend fun timetableForStation(
        routeId: String,
        stopId: String,
        serviceDate: LocalDate,
        dayType: OfficialServiceDayType,
        calendarSourceVersion: String
    ): OfficialTimetable? = withContext(Dispatchers.IO) {
        if (!validTimetableText(stopId) || !validTimetableText(calendarSourceVersion)) return@withContext null
        val data = load(routeId, dayType) ?: return@withContext null
        data.forServiceDate(data.indexesForStation(stopId), serviceDate, dayType, calendarSourceVersion)
    }

    /**
     * Resolve later alighting stations, rather than mistaking them for the next adjacent stop.
     * A name must resolve to ONE station in the selected line's publication. Repeated calls on
     * a loop are retained; paths only count trains where the target occurs after boarding.
     */
    suspend fun directionPaths(
        routeId: String,
        boardingStopId: String,
        targetStationName: String,
        dayType: OfficialServiceDayType
    ): List<OfficialTimetableDirectionPath> = withContext(Dispatchers.IO) {
        val data = load(routeId, dayType) ?: return@withContext emptyList()
        data.directionPaths(boardingStopId, targetStationName)
    }

    /** Direct same-line destination linking stays available when the external route API fails. */
    suspend fun directStationChoice(station: OfficialSubwayStation, targetName: String,
        day: OfficialTimetableCalendarDay): KakaoTransitChoice? {
        val table = timetableForStation(station.routeId,station.stopId,day.serviceDate,day.dayType,day.sourceVersion)
            ?: return null
        val target = timetableStationName(targetName)
        data class Path(val trip: OfficialTimetableTrip, val target: OfficialTimetableStop, val seconds: Int)
        val paths = table.trips.mapNotNull { trip ->
            val boarding = trip.stops.withIndex().singleOrNull { it.value.stopId == station.stopId } ?: return@mapNotNull null
            val end = trip.stops.drop(boarding.index+1).singleOrNull { timetableStationName(it.stopName) == target }
                ?: return@mapNotNull null
            val startAt = boarding.value.departureAtEpochMillis ?: boarding.value.arrivalAtEpochMillis ?: return@mapNotNull null
            val endAt = end.arrivalAtEpochMillis ?: end.departureAtEpochMillis ?: return@mapNotNull null
            val seconds = ((endAt-startAt)/1000L).takeIf { it in 1L..86400L }?.toInt() ?: return@mapNotNull null
            Path(trip,end,seconds)
        }
        val direction = paths.map { it.trip.directionId }.distinct().singleOrNull() ?: return null
        val targetId = paths.map { it.target.stopId }.distinct().singleOrNull() ?: return null
        val seconds = paths.map { it.seconds }.sorted().let { it[it.size/2] }
        return KakaoTransitChoice(seconds,0,station.lineName,station.name,null,firstTransitType="SUBWAY",
            officialBoardingStopId=station.stopId,directionName=direction,
            firstLegStopNames=listOf(station.name,paths.first { it.target.stopId == targetId }.target.stopName),
            alightingStopName=paths.first().target.stopName)
    }

    private suspend fun load(routeId: String, dayType: OfficialServiceDayType): IndexedOfficialTimetable? {
        if (!Regex("[0-9]{4}").matches(routeId)) return null
        val key = "${routeId}_${dayType.name}"
        return cacheMutex.withLock {
            cache[key]?.let { return@withLock it }
            if (key !in groups) return@withLock null
            val parsed = GZIPInputStream(openAsset("timetables/$key.json.gz"))
                .bufferedReader(Charsets.UTF_8).use { parseIndexedOfficialTimetable(it.readText()) }
            require(parsed.routeId == routeId && parsed.dayType == dayType)
            cache[key] = parsed
            parsed
        }
    }

    companion object {
        /** File-backed tests exercise the exact production parser without an Android Context. */
        internal fun fromAssets(openAsset: (String) -> InputStream) = OfficialTimetableRepository(openAsset)
    }
}

/** A query key only. Keeping raw publisher trainNo on every trip is mandatory. */
internal fun officialTimetableTrainDigits(trainNo: String): String? =
    Regex("[A-Za-z]*([0-9]+)[A-Za-z]*").matchEntire(trainNo)?.groupValues?.get(1)
        ?.trimStart('0')?.ifEmpty { "0" }

internal data class IndexedTimetableStation(
    val stopId: String,
    val stopName: String,
    val sourceStopId: String,
    val sourceStopName: String,
    val mapping: String
)

internal class IndexedOfficialTimetable(
    val routeId: String,
    val dayType: OfficialServiceDayType,
    val source: OfficialTimetableSource,
    private val stations: List<IndexedTimetableStation>,
    private val trips: JSONArray,
    private val trainIndex: JSONObject,
    private val stationIndex: JSONObject
) {
    val trainNumbers: List<String> = trainIndex.keys().asSequence().toList()

    fun indexesForTrain(trainNo: String): List<Int> = trainIndex.optJSONArray(trainNo).intList()
    fun indexesForStation(stopId: String): List<Int> = stationIndex.optJSONArray(stopId).intList()

    fun forServiceDate(indexes: List<Int>, date: LocalDate, type: OfficialServiceDayType,
                       calendarSourceVersion: String): OfficialTimetable? {
        if (indexes.isEmpty() || type != dayType || !validTimetableText(calendarSourceVersion)) return null
        val start = requireNotNull(officialTimetableTimeAt(date, "00:00:00", source.timeZone))
        val selected = indexes.map { index ->
            val trip = trips.getJSONArray(index)
            val calls = trip.getJSONArray(7)
            OfficialTimetableTrip(trip.getString(0), date, type, routeId, trip.getString(1),
                trip.getString(2), OfficialTrainOperationType.valueOf(trip.getString(3)), trip.getString(4),
                (0 until calls.length()).map { sequence ->
                    val stop = calls.getJSONArray(sequence)
                    val station = stations[stop.getInt(0)]
                    OfficialTimetableStop(sequence, station.stopId, station.stopName,
                        stop.secondsAt(1)?.let { start + it * 1_000L },
                        stop.secondsAt(2)?.let { start + it * 1_000L })
                })
        }
        return OfficialTimetable(source, "seoul",
            listOf(OfficialTimetableCalendarDay(date, type, calendarSourceVersion)), selected)
    }

    fun directionPaths(boardingStopId: String, targetStationName: String): List<OfficialTimetableDirectionPath> {
        if (!validTimetableText(boardingStopId) || targetStationName.isBlank()) return emptyList()
        val query = timetableStationName(targetStationName)
        val targetIds = stations.filter {
            query == timetableStationName(it.stopName) || query == timetableStationName(it.sourceStopName)
        }.map { it.stopId }.distinct()
        val targetId = targetIds.singleOrNull() ?: return emptyList()
        if (targetId == boardingStopId) return emptyList()
        data class Key(val direction: String, val variant: String, val operation: String)
        val next = linkedMapOf<Key, MutableSet<String>>()
        val counts = linkedMapOf<Key, Int>()
        for (index in indexesForStation(boardingStopId)) {
            val trip = trips.getJSONArray(index)
            val calls = trip.getJSONArray(7)
            val boardingIndexes = (0 until calls.length()).filter {
                stations[calls.getJSONArray(it).getInt(0)].stopId == boardingStopId
            }
            val usable = boardingIndexes.filter { boarding ->
                (boarding + 1 until calls.length()).any {
                    stations[calls.getJSONArray(it).getInt(0)].stopId == targetId
                }
            }
            if (usable.isEmpty()) continue
            val key = Key(trip.getString(1), trip.getString(2), trip.getString(3))
            for (boarding in usable) {
                next.getOrPut(key) { linkedSetOf() }.add(stations[calls.getJSONArray(boarding + 1).getInt(0)].stopId)
            }
            counts[key] = (counts[key] ?: 0) + 1
        }
        val targetName = stations.first { it.stopId == targetId }.stopName
        return counts.map { (key, count) -> OfficialTimetableDirectionPath(routeId, key.direction,
            key.variant, OfficialTrainOperationType.valueOf(key.operation), boardingStopId, targetId,
            targetName, next.getValue(key).toSet(), count, source) }
    }
}

internal fun parseIndexedOfficialTimetable(payload: String): IndexedOfficialTimetable {
    val json = JSONObject(payload)
    require(json.getInt("schemaVersion") == 1)
    val source = json.getJSONObject("source")
    val metadata = OfficialTimetableSource(
        authority = source.getString("authority"), dataset = source.getString("dataset"),
        version = source.getString("version"), publishedAtEpochMillis = source.getLong("publishedAtEpochMillis"),
        validFromServiceDate = source.optionalDate("validFromServiceDate"),
        validThroughServiceDate = source.optionalDate("validThroughServiceDate"),
        timeZone = source.getString("timeZone"), scheduleAsOfDate = source.optionalDate("scheduleAsOfDate")
    )
    val stations = json.getJSONArray("stations")
    return IndexedOfficialTimetable(json.getString("routeId"),
        OfficialServiceDayType.valueOf(json.getString("dayType")), metadata,
        (0 until stations.length()).map { index ->
            val station = stations.getJSONArray(index)
            IndexedTimetableStation(station.getString(0), station.getString(1), station.getString(2),
                station.getString(3), station.getString(4))
        }, json.getJSONArray("trips"), json.getJSONObject("trainIndex"), json.getJSONObject("stationIndex"))
}

private fun JSONObject.optionalDate(name: String): LocalDate? =
    if (!has(name) || isNull(name)) null else LocalDate.parse(getString(name))

private fun JSONArray?.intList(): List<Int> =
    if (this == null) emptyList() else (0 until length()).map(::getInt)

private fun JSONArray.secondsAt(index: Int): Long? = if (isNull(index)) null else getLong(index)

private fun timetableStationName(value: String): String {
    var name = value.replace(Regex("\\s+"), "").replace(Regex("\\([^()]*\\)"), "")
    // The selected line is already exact; only a terminal 역 suffix is a station alias.
    if (name.endsWith("역") && name.length > 1) name = name.dropLast(1)
    return name
}
