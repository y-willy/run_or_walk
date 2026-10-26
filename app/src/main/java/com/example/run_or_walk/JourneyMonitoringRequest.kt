package com.example.run_or_walk

import org.json.JSONObject

/** A user-confirmed static boarding choice. No GPS sample, arrival time or credential is serialized. */
internal data class JourneyMonitoringRequest(
    val boardingName: String,
    val boardingPoint: MapPoint,
    val identity: OfficialBoardingIdentity,
    val subwayStation: OfficialSubwayStation? = null,
    val busStop: OfficialSeoulBusStop? = null,
    val busRoute: OfficialSeoulBusRoute? = null,
    val settings: JourneySettings = JourneySettings(),
    val vibrationEnabled: Boolean = false,
    val gyeonggiMatch: GyeonggiOfficialMatch? = null,
    val subwayRoute: KakaoTransitChoice? = null
) {
    val isGyeonggi: Boolean get() = identity.authority == "GBIS"
    val isBus: Boolean get() = identity.authority in setOf("seoul-bus", "GBIS")
    val serviceLabel: String get() = if (isGyeonggi)
        gyeonggiMatch?.let { "${it.routeName} · ${it.destination}" }.orEmpty()
        else if (isBus) busRoute?.let { "${it.name} · ${it.direction}" }.orEmpty()
        else subwayStation?.let { "${it.lineName} · ${identity.directionId}" }.orEmpty()

    fun matches(identity: OfficialBoardingIdentity?, point: MapPoint?): Boolean =
        this.identity == identity && boardingPoint == point

    fun isSameJourney(other: JourneyMonitoringRequest): Boolean =
        identity == other.identity && boardingPoint == other.boardingPoint &&
            subwayStation == other.subwayStation && busStop == other.busStop && busRoute == other.busRoute &&
            gyeonggiMatch == other.gyeonggiMatch && subwayRoute == other.subwayRoute

    fun isValid(): Boolean {
        if (boardingName.isBlank() || boardingName.length > 200 || boardingName.any(Char::isISOControl) ||
            !boardingPoint.validMonitoringPoint() || !identity.isComplete ||
            listOf(identity.authority, identity.stopId, identity.routeId, identity.directionId, identity.serviceVariant)
                .any { it.length > 512 || it.any(Char::isISOControl) }) return false
        return when (identity.authority) {
            "GBIS" -> subwayStation == null && busStop == null && busRoute == null &&
                gyeonggiMatch?.let { it.isValid() && it.identity == identity && it.point == boardingPoint } == true
            "seoul" -> gyeonggiMatch == null && busStop == null && busRoute == null && subwayStation?.let {
                it.stopId == identity.stopId && it.routeId == identity.routeId &&
                    it.name.isNotBlank() && it.lineName.isNotBlank() &&
                    identity.directionId in setOf("상행", "하행", "내선", "외선")
            } == true
            "seoul-bus" -> gyeonggiMatch == null && subwayStation == null && busStop?.let { stop ->
                busRoute?.let { route -> stop.stopId == route.stopId && route.identity == identity &&
                    route.supported && route.routeType in setOf("2", "3", "4", "5", "6") &&
                    route.name.isNotBlank() && route.direction.isNotBlank() &&
                    stop.point == boardingPoint && stop.stopId.matches(Regex("[1-9][0-9]{8}")) &&
                    stop.arsId.matches(Regex("[0-9]{5}")) && stop.arsId != "00000" &&
                    route.routeId.matches(Regex("[1-9][0-9]{8}")) &&
                    route.stopOrder.toIntOrNull()?.let { it > 0 } == true
                } == true
            } == true
            else -> false
        }
    }
}

internal object JourneyMonitoringRequestCodec {
    fun encode(request: JourneyMonitoringRequest): String {
        require(request.isValid()) { "Invalid confirmed journey" }
        return JSONObject().put("version", 1).put("boardingName", request.boardingName)
            .put("x", request.boardingPoint.x).put("y", request.boardingPoint.y)
            .put("identity", JSONObject().put("authority", request.identity.authority)
                .put("stopId", request.identity.stopId).put("routeId", request.identity.routeId)
                .put("directionId", request.identity.directionId).put("serviceVariant", request.identity.serviceVariant))
            .put("settings", JSONObject(JourneySettingsCodec.encode(request.settings)))
            .put("vibrationEnabled", request.vibrationEnabled).apply {
                request.gyeonggiMatch?.let { put("gyeonggiMatch", it.toSessionJson()) }
                request.subwayRoute?.let { put("subwayRoute", JourneyTransitRoute("monitor-route", it).toJson()) }
                request.subwayStation?.let {
                    put("subwayStation", JSONObject().put("stopId", it.stopId).put("routeId", it.routeId)
                        .put("name", it.name).put("lineName", it.lineName))
                }
                request.busStop?.let {
                    put("busStop", JSONObject().put("stopId", it.stopId).put("arsId", it.arsId).put("name", it.name))
                }
                request.busRoute?.let {
                    put("busRoute", JSONObject().put("stopId", it.stopId).put("routeId", it.routeId)
                        .put("name", it.name).put("stopOrder", it.stopOrder).put("direction", it.direction)
                        .put("routeType", it.routeType).put("supported", it.supported))
                }
            }.toString()
    }

    fun decode(payload: String?): JourneyMonitoringRequest? = runCatching {
        if (payload.isNullOrBlank() || payload.length > 16_384) return null
        val root = JSONObject(payload)
        if (root.optInt("version") != 1) return null
        fun JSONObject.text(key: String): String = (opt(key) as? String)?.takeIf {
            it.isNotBlank() && it.length <= 512 && it.none(Char::isISOControl)
        } ?: error("Invalid journey field")
        val point = MapPoint(root.getDouble("x"), root.getDouble("y"))
        val raw = root.getJSONObject("identity")
        val identity = OfficialBoardingIdentity(raw.text("authority"), raw.text("stopId"), raw.text("routeId"),
            raw.text("directionId"), raw.text("serviceVariant"))
        val station = root.optJSONObject("subwayStation")?.let {
            OfficialSubwayStation(it.text("stopId"), it.text("routeId"), it.text("name"), it.text("lineName"))
        }
        val stop = root.optJSONObject("busStop")?.let {
            OfficialSeoulBusStop(it.text("stopId"), it.text("arsId"), it.text("name"), point)
        }
        val route = root.optJSONObject("busRoute")?.let {
            OfficialSeoulBusRoute(it.text("stopId"), it.text("routeId"), it.text("name"), it.text("stopOrder"),
                it.text("direction"), it.text("routeType"), it.opt("supported") == true)
        }
        JourneyMonitoringRequest(root.text("boardingName"), point, identity, station, stop, route,
            JourneySettingsCodec.decode(root.optJSONObject("settings")?.toString()),
            root.opt("vibrationEnabled") == true,
            decodeGyeonggiMatch(root.optJSONObject("gyeonggiMatch")),
            root.optJSONObject("subwayRoute")?.route()?.kakao).takeIf { it.isValid() }
    }.getOrNull()
}

private fun MapPoint.validMonitoringPoint(): Boolean =
    x.isFinite() && y.isFinite() && x in -180.0..180.0 && y in -90.0..90.0
