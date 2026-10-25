package com.example.run_or_walk

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Validate actual provider geometry before attaching GPS/boarding coordinates to its duration. */
internal fun validWalkingAccess(route: KakaoWalkingRoute, origin: MapPoint, destination: MapPoint): Boolean {
    fun valid(point: MapPoint) = point.x.isFinite() && point.y.isFinite() &&
        point.x in -180.0..180.0 && point.y in -90.0..90.0
    if (!valid(origin) || !valid(destination) || route.distanceMeters < 0 || route.durationSeconds < 0 ||
        route.points.size < 2 || route.points.any { !valid(it) }) return false
    if (walkingAccessDistance(route.points.first(), origin) > 50.0 ||
        walkingAccessDistance(route.points.last(), destination) > 50.0) return false
    val straight = walkingAccessDistance(origin, destination)
    if (route.distanceMeters == 0 || route.durationSeconds == 0)
        return route.distanceMeters == 0 && route.durationSeconds == 0 && straight <= 20.0
    return route.distanceMeters + 50.0 >= straight
}

private fun walkingAccessDistance(a: MapPoint, b: MapPoint): Double {
    val lat = Math.toRadians(b.y - a.y)
    val lon = Math.toRadians(b.x - a.x)
    val h = sin(lat / 2).let { it * it } + cos(Math.toRadians(a.y)) *
        cos(Math.toRadians(b.y)) * sin(lon / 2).let { it * it }
    return 6_371_000.0 * 2 * asin(sqrt(h.coerceIn(0.0, 1.0)))
}
