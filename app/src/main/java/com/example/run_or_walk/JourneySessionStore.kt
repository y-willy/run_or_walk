package com.example.run_or_walk

import android.content.Context
import org.json.JSONObject

/** A saved selection is not a saved GPS fix, walking response or realtime prediction. */
internal data class JourneySessionSnapshot(
    val mode: FeatureMode,
    val screens: List<JourneySelectionSnapshot>
)

internal data class JourneySelectionSnapshot(
    val mode: FeatureMode,
    val destinationQuery: String,
    val destination: JourneyPlace?,
    val stopQuery: String,
    val stop: JourneyPlace?,
    val selectedRoute: JourneyTransitRoute?,
    val boarding: JourneyPlace?,
    val boardingSource: BoardingPointSource?,
    val boardingChosenByUser: Boolean,
    val boardingMatchedAutomatically: Boolean,
    val stationQuery: String,
    val station: OfficialSubwayStation?,
    val busStopQuery: String,
    val busStop: OfficialSeoulBusStop?,
    val busRoute: OfficialSeoulBusRoute?,
    val confirmedIdentity: OfficialBoardingIdentity?,
    val alertsEnabled: Boolean,
    val detailsExpanded: Boolean
)

internal fun captureJourneySession(
    mode: FeatureMode,
    screens: Map<FeatureMode, JourneyScreenState>
): JourneySessionSnapshot = JourneySessionSnapshot(mode, FeatureMode.entries.map { key ->
    val state = screens.getValue(key)
    JourneySelectionSnapshot(key, state.destination.query, state.destination.selected,
        state.stop.query, state.stop.selected, state.selectedRoute, state.boarding,
        state.boardingSource, state.boardingChosenByUser, state.boardingMatchedAutomatically,
        state.stationQuery, state.selectedStation, state.busStopQuery, state.selectedBusStop,
        state.selectedBusRoute, state.confirmedServiceIdentity, state.alertsEnabled,
        state.detailsExpanded)
})

/** Use fresh screen instances: dynamic data and in-flight generations always start empty. */
internal fun restoreJourneyScreens(snapshot: JourneySessionSnapshot?): Map<FeatureMode, JourneyScreenState> {
    val gate = AutomaticAlertGate()
    return FeatureMode.entries.associateWith { mode ->
        JourneyScreenState(mode, gate).apply {
            snapshot?.screens?.singleOrNull { it.mode == mode }?.let { saved ->
                destination.query = saved.destinationQuery
                destination.selected = saved.destination
                stop.query = saved.stopQuery
                stop.selected = saved.stop
                selectedRoute = saved.selectedRoute
                // Only the user's selected itinerary is restored; alternate results need a new search.
                routes = listOfNotNull(saved.selectedRoute)
                routeSearchCompleted = saved.selectedRoute != null
                boarding = saved.boarding
                boardingSource = saved.boardingSource
                boardingChosenByUser = saved.boarding != null && saved.boardingChosenByUser
                boardingMatchedAutomatically = saved.boarding != null && saved.boardingMatchedAutomatically
                stationQuery = saved.stationQuery
                busStopQuery = saved.busStopQuery
                if (saved.boarding != null && boardingConfirmed) {
                    if (isBusJourney) {
                        selectedBusStop = saved.busStop?.takeIf { it.point == saved.boarding.point }
                        selectedBusRoute = saved.busRoute?.takeIf { it.stopId == selectedBusStop?.stopId }
                        busStopOptions = listOfNotNull(selectedBusStop)
                        busStopsSearched = selectedBusStop != null
                        busRouteOptions = listOfNotNull(selectedBusRoute)
                        confirmedServiceIdentity = saved.confirmedIdentity?.takeIf {
                            it == selectedBusRoute?.identity
                        }
                    } else {
                        selectedStation = saved.station
                        stationOptions = listOfNotNull(saved.station)
                        stationsSearched = saved.station != null
                        confirmedServiceIdentity = saved.confirmedIdentity?.takeIf {
                            it.authority == "seoul" && it.stopId == saved.station?.stopId &&
                                it.routeId == saved.station?.routeId
                        }
                    }
                }
                alertsEnabled = saved.alertsEnabled
                detailsExpanded = saved.detailsExpanded
                mapCenter = saved.boarding?.point ?: saved.destination?.point
                if (saved.boarding != null) status = "이전 선택을 복원했어요. 위치와 도착정보를 다시 확인해요."
            }
        }
    }
}

internal class JourneySessionStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("journey_session", Context.MODE_PRIVATE)

    fun load(): JourneySessionSnapshot? = runCatching {
        decodeJourneySession(preferences.getString("selection_v1", null))
    }.getOrNull()

    fun save(snapshot: JourneySessionSnapshot) {
        // The immutable snapshot was captured on the UI thread; no mutable UI state crosses storage.
        val json = runCatching { encodeJourneySession(snapshot) }.getOrNull()
            ?.takeIf { it.length <= MAX_SESSION_CHARACTERS } ?: return
        preferences.edit().putString("selection_v1", json).apply()
    }
}

private const val SESSION_VERSION = 1
private const val MAX_SESSION_CHARACTERS = 40_000

internal fun encodeJourneySession(snapshot: JourneySessionSnapshot): String = JSONObject()
    .put("version", SESSION_VERSION).put("mode", snapshot.mode.name)
    .put("screens", org.json.JSONArray(snapshot.screens.map { saved ->
        JSONObject().put("mode", saved.mode.name)
            .put("destinationQuery", saved.destinationQuery).putOptional("destination", saved.destination?.toJson())
            .put("stopQuery", saved.stopQuery).putOptional("stop", saved.stop?.toJson())
            .putOptional("selectedRoute", saved.selectedRoute?.toJson())
            .putOptional("boarding", saved.boarding?.toJson())
            .putOptional("boardingSource", saved.boardingSource?.name)
            .put("boardingChosenByUser", saved.boardingChosenByUser)
            .put("boardingMatchedAutomatically", saved.boardingMatchedAutomatically)
            .put("stationQuery", saved.stationQuery).putOptional("station", saved.station?.toJson())
            .put("busStopQuery", saved.busStopQuery).putOptional("busStop", saved.busStop?.toJson())
            .putOptional("busRoute", saved.busRoute?.toJson())
            .putOptional("confirmedIdentity", saved.confirmedIdentity?.toJson())
            .put("alertsEnabled", saved.alertsEnabled).put("detailsExpanded", saved.detailsExpanded)
    })).toString()

/** Unknown versions, excessive input and invalid identities fail closed to an empty selection. */
internal fun decodeJourneySession(text: String?): JourneySessionSnapshot? {
    if (text == null || text.length > MAX_SESSION_CHARACTERS) return null
    return runCatching {
        val root = JSONObject(text)
        if (root.opt("version") != SESSION_VERSION) return null
        val mode = root.enum<FeatureMode>("mode") ?: return null
        val rows = root.optJSONArray("screens") ?: return null
        if (rows.length() > FeatureMode.entries.size) return null
        val screens = (0 until rows.length()).map { index ->
            val row = rows.optJSONObject(index) ?: return null
            val screenMode = row.enum<FeatureMode>("mode") ?: return null
            JourneySelectionSnapshot(screenMode, row.query("destinationQuery"), row.place("destination"),
                row.query("stopQuery"), row.place("stop"), row.optJSONObject("selectedRoute")?.route(),
                row.place("boarding"), row.enum<BoardingPointSource>("boardingSource"),
                row.flag("boardingChosenByUser"), row.flag("boardingMatchedAutomatically"),
                row.query("stationQuery"), row.optJSONObject("station")?.station(),
                row.query("busStopQuery"), row.optJSONObject("busStop")?.busStop(),
                row.optJSONObject("busRoute")?.busRoute(), row.optJSONObject("confirmedIdentity")?.identity(),
                row.flag("alertsEnabled"), row.flag("detailsExpanded"))
        }
        if (screens.map { it.mode }.distinct().size != screens.size) return null
        JourneySessionSnapshot(mode, screens)
    }.getOrNull()
}

private fun JourneyPlace.toJson() = JSONObject().put("id", id).put("name", name).put("detail", detail)
    .put("point", point.toJson()).putOptional("categoryGroupCode", categoryGroupCode)
    .putOptional("categoryName", categoryName)
private fun JSONObject.place(key: String): JourneyPlace? {
    val item = optJSONObject(key) ?: return null
    return JourneyPlace(item.text("id", 160) ?: return null, item.text("name", 200) ?: return null,
        item.text("detail", 512).orEmpty(), item.optJSONObject("point")?.point() ?: return null,
        item.text("categoryGroupCode", 16), item.text("categoryName", 256))
}
private fun MapPoint.toJson() = JSONObject().put("x", x).put("y", y)
private fun JSONObject.point(): MapPoint? {
    val x = (opt("x") as? Number)?.toDouble() ?: return null
    val y = (opt("y") as? Number)?.toDouble() ?: return null
    return MapPoint(x, y).takeIf { x.isFinite() && y.isFinite() && x in -180.0..180.0 && y in -90.0..90.0 }
}
private fun JourneyTransitRoute.toJson(): JSONObject {
    val choice = kakao
    return JSONObject().put("id", id).put("totalSeconds", choice.totalSeconds)
        .putOptional("transfers", choice.transfers).put("serviceName", choice.serviceName)
        .put("boardingStopName", choice.boardingStopName).putOptional("boardingPoint", choice.boardingPoint?.toJson())
        .put("boardingSource", choice.boardingSource.name).put("firstTransitType", choice.firstTransitType)
        .putOptional("totalWalkingSeconds", choice.totalWalkingSeconds).putOptional("boardingStopId", choice.boardingStopId)
        .putOptional("officialBoardingStopId", choice.officialBoardingStopId).putOptional("transitLineId", choice.transitLineId)
        .putOptional("directionName", choice.directionName)
}
private fun JSONObject.route(): JourneyTransitRoute? {
    val id = text("id", 160) ?: return null
    val total = integer("totalSeconds", 1, 172_800) ?: return null
    val type = text("firstTransitType", 16)?.takeIf { it in setOf("BUS", "SUBWAY") } ?: return null
    val source = enum<BoardingPointSource>("boardingSource") ?: BoardingPointSource.UNKNOWN
    return JourneyTransitRoute(id, KakaoTransitChoice(total, integer("transfers", 0, 20),
        text("serviceName", 200).orEmpty(), text("boardingStopName", 200).orEmpty(),
        optJSONObject("boardingPoint")?.point(), source, type, integer("totalWalkingSeconds", 0, 86_400),
        text("boardingStopId", 160), text("officialBoardingStopId", 160), text("transitLineId", 160),
        text("directionName", 200)))
}
private fun OfficialSubwayStation.toJson() = JSONObject().put("stopId", stopId).put("routeId", routeId)
    .put("name", name).put("lineName", lineName)
private fun JSONObject.station(): OfficialSubwayStation? {
    return OfficialSubwayStation(
        text("stopId", 10)?.takeIf { it.matches(Regex("[0-9]{10}")) } ?: return null,
        text("routeId", 4)?.takeIf { it.matches(Regex("[0-9]{4}")) } ?: return null,
        text("name", 100) ?: return null, text("lineName", 100) ?: return null)
}
private fun OfficialSeoulBusStop.toJson() = JSONObject().put("stopId", stopId).put("arsId", arsId)
    .put("name", name).putOptional("point", point?.toJson())
private fun JSONObject.busStop(): OfficialSeoulBusStop? {
    return OfficialSeoulBusStop(
        text("stopId", 9)?.takeIf(::busId) ?: return null,
        text("arsId", 5)?.takeIf { it.matches(Regex("[0-9]{5}")) && it != "00000" } ?: return null,
        text("name", 200) ?: return null, optJSONObject("point")?.point() ?: return null)
}
private fun OfficialSeoulBusRoute.toJson() = JSONObject().put("stopId", stopId).put("routeId", routeId)
    .put("name", name).put("stopOrder", stopOrder).put("direction", direction).put("routeType", routeType)
    .put("supported", supported)
private fun JSONObject.busRoute(): OfficialSeoulBusRoute? {
    val type = text("routeType", 2)?.takeIf { it in setOf("2", "3", "4", "5", "6") } ?: return null
    if (!flag("supported")) return null
    return OfficialSeoulBusRoute(text("stopId", 9)?.takeIf(::busId) ?: return null,
        text("routeId", 9)?.takeIf(::busId) ?: return null, text("name", 100) ?: return null,
        text("stopOrder", 4)?.takeIf { it.matches(Regex("[0-9]{1,4}")) && it.toInt() > 0 } ?: return null,
        text("direction", 200) ?: return null, type, true)
}
private fun OfficialBoardingIdentity.toJson() = JSONObject().put("authority", authority).put("stopId", stopId)
    .put("routeId", routeId).put("directionId", directionId).put("serviceVariant", serviceVariant)
private fun JSONObject.identity(): OfficialBoardingIdentity? {
    val value = OfficialBoardingIdentity(text("authority", 16) ?: return null,
        text("stopId", 12) ?: return null, text("routeId", 12) ?: return null,
        text("directionId", 40) ?: return null, text("serviceVariant", 2_048) ?: return null)
    return value.takeIf { it.isComplete && it.authority in setOf("seoul", "seoul-bus") }
}
private fun busId(value: String) = value.matches(Regex("[1-9][0-9]{8}"))
private fun JSONObject.putOptional(key: String, value: Any?): JSONObject = put(key, value ?: JSONObject.NULL)
private fun JSONObject.text(key: String, limit: Int): String? = (opt(key) as? String)?.takeIf {
    it.isNotBlank() && it.length <= limit && it.none(Char::isISOControl)
}
private fun JSONObject.query(key: String): String = (opt(key) as? String)?.takeIf {
    it.length <= 200 && it.none(Char::isISOControl)
}.orEmpty()
private fun JSONObject.flag(key: String): Boolean = opt(key) as? Boolean ?: false
private fun JSONObject.integer(key: String, min: Int, max: Int): Int? {
    val number = (opt(key) as? Number)?.toDouble() ?: return null
    return number.takeIf { it.isFinite() && it % 1.0 == 0.0 && it in min.toDouble()..max.toDouble() }?.toInt()
}
private inline fun <reified T : Enum<T>> JSONObject.enum(key: String): T? =
    (opt(key) as? String)?.let { value -> enumValues<T>().firstOrNull { it.name == value } }
