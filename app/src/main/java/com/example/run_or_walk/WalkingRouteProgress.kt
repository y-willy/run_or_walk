package com.example.run_or_walk

import kotlin.math.*

internal data class WalkingRouteProgress(val remainingMeters: Int,val providerSeconds: Int,val onRoute: Boolean)

/** Projects a fresh fix on actual provider geometry. Stationary time never advances the route. */
internal class WalkingRouteTracker(val origin: MapPoint,val route: KakaoWalkingRoute,val fetchedAt: Long) {
    private var progressMeters=0.0
    private var previous: GuidanceLocation?=null
    private var lastObservedAt=Long.MIN_VALUE
    private var lastFix: GuidanceLocation?=null
    fun update(fix: GuidanceLocation,now: Long): WalkingRouteProgress? {
        if(!validPedestrianFix(fix,now) || fix.observedAtEpochMillis < lastObservedAt) return null
        lastObservedAt=fix.observedAtEpochMillis
        val recent=lastFix
        lastFix=fix
        if(recent != null) {
            val seconds=(fix.observedAtEpochMillis-recent.observedAtEpochMillis).coerceAtLeast(0)/1000.0
            if(pedestrianDistance(recent.point,fix.point) > seconds*4.5+max(8.0,fix.accuracyMeters)) return null
        }
        if(fix.speedMetersPerSecond?.let { it > 4.5 } == true || now-fetchedAt !in 0..120_000L || route.points.size < 2 || route.distanceMeters < 0) return null
        val projection=projectOnWalkingGeometry(route.points,fix.point) ?: return null
        val onRoute=projection.distanceFromPath <= max(20.0,fix.accuracyMeters)
        val last=previous
        if(last != null && fix.observedAtEpochMillis < last.observedAtEpochMillis) return null
        if(!onRoute) return WalkingRouteProgress(route.distanceMeters,route.durationSeconds,false)
        // Rebase on the first fix; do not turn location jitter or a vehicle-speed jump into progress.
        val along=projection.alongPathMeters
        val allowed=last?.let { (fix.observedAtEpochMillis-it.observedAtEpochMillis).coerceAtLeast(0)/1000.0*3.5+5 } ?: 35.0
        val displacement=last?.let { pedestrianDistance(it.point,fix.point) } ?: pedestrianDistance(origin,fix.point)
        val stationary=fix.speedMetersPerSecond?.let { it < 0.45 } == true ||
            displacement < max(3.0,fix.accuracyMeters*0.35)
        val forward=along-progressMeters
        var accepted=false
        if(!stationary && forward in 0.0..allowed && along <= projection.totalPathMeters) { progressMeters=along; accepted=true }
        // Allow genuine walking back along the path; never monotonically erase a detour.
        if(!stationary && forward < -max(8.0,fix.accuracyMeters) && abs(forward) <= allowed) { progressMeters=along; accepted=true }
        if(previous == null || accepted) previous=fix
        val fraction=(1-progressMeters/projection.totalPathMeters).coerceIn(0.0,1.0)
        return WalkingRouteProgress(ceil(route.distanceMeters*fraction).toInt(),ceil(route.durationSeconds*fraction).toInt(),true)
    }
}
internal data class WalkingProjection(val alongPathMeters: Double,val distanceFromPath: Double,val totalPathMeters: Double)
internal fun projectOnWalkingGeometry(points: List<MapPoint>,point: MapPoint): WalkingProjection? {
    if(points.size < 2 || points.any { !it.x.isFinite() || !it.y.isFinite() }) return null
    var cumulative=0.0; var nearest=Double.POSITIVE_INFINITY; var along=0.0
    val lonScale=111_195.0*cos(Math.toRadians(point.y)); val latScale=111_195.0
    points.zipWithNext().forEach { (a,b) ->
        val ax=(a.x-point.x)*lonScale; val ay=(a.y-point.y)*latScale
        val dx=(b.x-a.x)*lonScale; val dy=(b.y-a.y)*latScale
        val length=hypot(dx,dy)
        if(length > 0) {
            val fraction=(-(ax*dx+ay*dy)/(length*length)).coerceIn(0.0,1.0)
            val distance=hypot(ax+fraction*dx,ay+fraction*dy)
            if(distance < nearest) { nearest=distance; along=cumulative+fraction*length }
        }
        cumulative+=length
    }
    return if(cumulative <= 0 || !cumulative.isFinite()) null else WalkingProjection(along,nearest,cumulative)
}
internal fun shouldRefreshWalkingRoute(tracker: WalkingRouteTracker?,fix: GuidanceLocation,now: Long,lastRequestedAt: Long): Boolean {
    if(!validPedestrianFix(fix,now) || fix.speedMetersPerSecond?.let { it > 4.5 } == true || now-lastRequestedAt < 15_000L) return false
    if(tracker == null || now-tracker.fetchedAt >= 90_000L) return true
    val projection=projectOnWalkingGeometry(tracker.route.points,fix.point)
    return projection == null || projection.distanceFromPath > max(25.0,fix.accuracyMeters) ||
        pedestrianDistance(tracker.origin,fix.point) >= 60.0
}
