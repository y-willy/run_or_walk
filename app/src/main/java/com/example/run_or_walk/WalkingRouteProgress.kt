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
        if(!onRoute) {
            // Preserve only the provider's full summary near its verified requested start;
            // a snapped first point is not evidence that the user has left the route.
            if(route.requestedOrigin==origin && route.requestedDestination!=null &&
                pedestrianDistance(fix.point,origin)<=35.0 && validWalkingAccess(route,origin,route.requestedDestination))
                return WalkingRouteProgress(route.distanceMeters,route.durationSeconds,true)
            return WalkingRouteProgress(route.distanceMeters,route.durationSeconds,false)
        }
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
    if(!validPedestrianFix(fix,now) || fix.speedMetersPerSecond?.let { it > 4.5 } == true || now-lastRequestedAt < WALKING_REFRESH_MILLIS) return false
    if(tracker == null || now-tracker.fetchedAt >= WALKING_REFRESH_MILLIS) return true
    val projection=projectOnWalkingGeometry(tracker.route.points,fix.point)
    return projection == null || projection.distanceFromPath > max(25.0,fix.accuracyMeters) ||
        pedestrianDistance(tracker.origin,fix.point) >= 60.0
}

/** UI and service retain exactly the same aged geometry while a new fix/route is pending. */
internal data class LiveWalkingEvidence(
    val location: GuidanceLocation?,val walking: GuidanceWalkingRoute?,
    val distanceMeters: Int?,val providerSeconds: Int?,val points: List<MapPoint>,
    val calculatedAtEpochMillis:Long?=null
)
internal fun updateLiveWalkingEvidence(previous: LiveWalkingEvidence,tracker: WalkingRouteTracker?,
    fix: GuidanceLocation?,identity: OfficialBoardingIdentity,boarding: MapPoint,
    settings: JourneySettings,calibratedSpeed: Double?,now: Long,allowRecalculation:Boolean=true): LiveWalkingEvidence {
    val previousLocation=previous.location?.takeIf { locationSampleFailure(it,now)==null }
    val candidate=fix?.takeIf { locationSampleFailure(it,now)==null &&
        (previous.location?.observedAtEpochMillis?.let { time -> it.observedAtEpochMillis>=time } != false) }
    var location=candidate ?: previousLocation
    val effectivePace=usableWalkingCalibration(calibratedSpeed,fix ?: location,now)
    val progress=candidate?.let { tracker?.update(it,now) }
    if(progress?.onRoute==true && tracker!=null && tracker.route.points.lastOrNull()?.let {
            pedestrianDistance(it,boarding)<=150.0 }==true) {
        val prior=previous.walking
        if(!allowRecalculation && prior!=null && previous.distanceMeters?.let {progress.remainingMeters<=it}==true &&
            prior.boardingIdentity==identity && prior.fetchedAtEpochMillis==tracker.fetchedAt &&
            now-prior.fetchedAtEpochMillis in 0L..minOf(prior.validForMillis,120000L)) {
            // Validate the unchanged provider geometry with every GPS fix, but retain
            // conservative last-calculated distance/time until the 30-second computation.
            return previous.copy(location=candidate,walking=prior.copy(origin=requireNotNull(candidate).point))
        }
        return LiveWalkingEvidence(candidate,GuidanceWalkingRoute(requireNotNull(candidate).point,boarding,identity,
            settings.walkingSeconds(progress.remainingMeters,progress.providerSeconds,effectivePace),
            tracker.fetchedAt,120_000L,progress.remainingMeters),progress.remainingMeters,progress.providerSeconds,tracker.route.points,now)
    }
    if(progress?.onRoute==false) return LiveWalkingEvidence(location,null,null,null,emptyList())
    // A precise but rejected jump/vehicle sample must not replace the preceding valid fix.
    if(candidate!=null && validPedestrianFix(candidate,now) && tracker!=null &&
        now-tracker.fetchedAt in 0L..120_000L) location=previousLocation
    val walking=previous.walking?.takeIf {
        it.boardingIdentity==identity && pedestrianDistance(it.destination,boarding)<=15.0 &&
            now-it.fetchedAtEpochMillis in 0L..minOf(it.validForMillis,120_000L) &&
            (location==null || pedestrianDistance(it.origin,location.point)<=35.0)
    }
    val distance=previous.distanceMeters.takeIf { walking!=null }
    val provider=previous.providerSeconds.takeIf { walking!=null }
    val adjusted=walking?.let { value -> if(allowRecalculation && distance!=null && provider!=null)
        value.copy(durationSeconds=settings.walkingSeconds(distance,provider,effectivePace)) else value }
    return LiveWalkingEvidence(location,adjusted,distance,provider,previous.points.takeIf { walking!=null }.orEmpty(),if(allowRecalculation && adjusted!=null && location!=null) now else previous.calculatedAtEpochMillis)
}
