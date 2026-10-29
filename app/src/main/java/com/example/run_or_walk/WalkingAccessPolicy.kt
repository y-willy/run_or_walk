package com.example.run_or_walk

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Camera bounds only; missing route geometry never becomes a fabricated polyline. */
internal fun walkingMapViewportPoints(route: List<MapPoint>, current: MapPoint?, boarding: MapPoint?,
    otherSegments: List<List<MapPoint>>): List<MapPoint> {
    val pins = listOfNotNull(current,boarding)
    val points = if (boarding != null) route + pins else otherSegments.flatten() + pins
    return points.filter { it.x.isFinite() && it.y.isFinite() && it.x in -180.0..180.0 && it.y in -90.0..90.0 }.distinct()
}

/** Vehicle ETA and destination edits are deliberately absent from the GPS-to-departure cache key. */
internal fun shouldRefreshAccessMap(storedOrigin: MapPoint?, storedDeparture: MapPoint?,
    currentOrigin: MapPoint, departure: MapPoint, hasGeometry: Boolean): Boolean =
    !hasGeometry || storedDeparture != departure || storedOrigin == null ||
        walkingAccessDistance(storedOrigin,currentOrigin) > 35.0

/** Validate actual provider geometry before attaching GPS/boarding coordinates to its duration. */
internal fun validWalkingAccess(route: KakaoWalkingRoute, origin: MapPoint, destination: MapPoint): Boolean {
    fun valid(point: MapPoint) = point.x.isFinite() && point.y.isFinite() &&
        point.x in -180.0..180.0 && point.y in -90.0..90.0
    if (!valid(origin) || !valid(destination) || route.distanceMeters < 0 || route.durationSeconds < 0 ||
        route.points.size < 2 || route.points.any { !valid(it) }) return false
    val hasRequest=route.requestedOrigin!=null || route.requestedDestination!=null
    if(hasRequest && (route.requestedOrigin==null || route.requestedDestination!=destination ||
        walkingAccessDistance(requireNotNull(route.requestedOrigin),origin)>35.0)) return false
    val bound=hasRequest
    // For request-bound successful responses, a snapped endpoint must still lie within
    // the provider's total walking distance. Never add a fabricated connector/polyline.
    val endpointLimit=if(bound) maxOf(50.0,route.distanceMeters.toDouble()) else 50.0
    if (walkingAccessDistance(route.points.first(), origin) > endpointLimit ||
        walkingAccessDistance(route.points.last(), destination) > endpointLimit) return false
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

/** Map-only requests: visibility, one in-flight request, 30s floor and bounded failure backoff. */
internal class MapGeometryRequestGate {
    private var key:Any?=null
    private var due=Long.MIN_VALUE
    private var failures=0
    private var inFlight=false
    var lastSuccessElapsed:Long?=null; private set
    fun begin(queryKey:Any,nowElapsed:Long,visible:Boolean):Boolean {
        if(!visible || inFlight) return false
        if(key!=queryKey) {key=queryKey;due=Long.MIN_VALUE;failures=0;lastSuccessElapsed=null}
        if(nowElapsed<due) return false
        inFlight=true;due=nowElapsed+30000L
        return true
    }
    fun complete(success:Boolean,nowElapsed:Long) {
        inFlight=false
        if(success) {failures=0;lastSuccessElapsed=nowElapsed}
        else {failures++;due=maxOf(due,nowElapsed+minOf(300000L,30000L*(1L shl minOf(failures-1,4))))}
    }
    fun isGeometryFresh(nowElapsed:Long):Boolean=lastSuccessElapsed?.let {nowElapsed-it in 0L..120000L}==true
}

/** Store the actual provider metrics and geometry together, with their original receipt time. */
@Suppress("UNUSED_PARAMETER")
internal fun JourneyScreenState.storeWalkingResult(location:GuidanceLocation,place:JourneyPlace,
    identity:OfficialBoardingIdentity?,route:KakaoWalkingRoute,settings:JourneySettings,pace:Double?,
    fetchedAt:Long,now:Long):Boolean {
    if(!validWalkingAccess(route,location.point,place.point)) throw KakaoApiException(KakaoFailure.INVALID_RESPONSE)
    if(boarding?.point!=place.point ||
        locationSampleFailure(location,now)!=null || now-fetchedAt !in 0L..120_000L ||
        this.location?.let {pedestrianDistance(it.point,location.point)>35.0}==true) return false
    walkingRefreshCadence.acceptExternalResult()
    walkingTracker=WalkingRouteTracker(location.point,route,fetchedAt)
    walkingCalculatedAtEpochMillis=now
    walkingCalibrationMetersPerSecond=usableWalkingCalibration(pace,location,now)
    walkRoute=WalkRoute(route.distanceMeters,route.durationSeconds)
    walkFetchedAtEpochMillis=fetchedAt;walkOrigin=location.point;walkingPoints=route.points
    guidanceWalkingRoute=confirmedServiceIdentity?.let {GuidanceWalkingRoute(location.point,place.point,it,
        settings.walkingSeconds(route.distanceMeters,route.durationSeconds,walkingCalibrationMetersPerSecond),fetchedAt,120_000L,route.distanceMeters)}
    return true
}

/** A blue path to a different departure is not evidence of time to the actual boarding stop. */
internal fun JourneyScreenState.reuseMapWalkingForBoarding(location:GuidanceLocation,settings:JourneySettings,
    pace:Double?,now:Long):Boolean {
    val place=boarding ?: return false
    val cached=mapAccessWalking ?: return false
    if(mapAccessIsReference || mapAccessDestination!=departure.selected?.point ||
        now-cached.fetchedAt !in 0L..120_000L || pedestrianDistance(cached.origin,location.point)>25.0 ||
        !validWalkingAccess(cached.route,location.point,place.point)) return false
    return storeWalkingResult(location,place,confirmedServiceIdentity,cached.route,settings,pace,cached.fetchedAt,now)
}

/** Display a verified physical walking route even while official service binding is pending.
 * The movement calculator still independently requires the official boarding identity/ETA.
 */
internal fun JourneyScreenState.walkingDisplay(location:GuidanceLocation?,settings:JourneySettings,
    pace:Double?,now:Long):WalkRoute? {
    val fix=location?.takeIf {locationSampleFailure(it,now)==null} ?: return null
    val place=boarding ?: return null
    val raw=walkRoute ?: return null
    val origin=walkOrigin ?: return null
    val fetched=walkFetchedAtEpochMillis ?: return null
    val tracker=walkingTracker
    val source=tracker?.route ?: KakaoWalkingRoute(raw.distanceMeters,raw.durationSeconds,walkingPoints)
    val sourceOrigin=tracker?.origin ?: origin
    if(now-fetched !in 0L..120_000L || pedestrianDistance(origin,fix.point)>35.0 ||
        raw.distanceMeters<0 || raw.durationSeconds<0 || raw.distanceMeters>source.distanceMeters ||
        tracker?.let {it.fetchedAt!=fetched}==true || !validWalkingAccess(source,sourceOrigin,place.point)) return null
    return raw.copy(durationSeconds=settings.walkingSeconds(raw.distanceMeters,raw.durationSeconds,
        usableWalkingCalibration(pace,fix,now)))
}

/** Bind validated displayed metrics in the same render as official identity/ETA becomes ready,
 * without waiting for another GPS callback, HTTP request or asynchronous effect.
 */
internal fun JourneyScreenState.guidanceWalkingAt(location:GuidanceLocation?,
    identity:OfficialBoardingIdentity?,settings:JourneySettings,pace:Double?,now:Long):GuidanceWalkingRoute? {
    if(identity==null || !identity.isComplete || confirmedServiceIdentity?.sameBoardingAccessAs(identity)!=true || !boardingConfirmed) return null
    val fix=location ?: return null
    val place=boarding ?: return null
    val walk=walkingDisplay(fix,settings,pace,now) ?: return null
    val fetched=walkFetchedAtEpochMillis ?: return null
    return GuidanceWalkingRoute(fix.point,place.point,identity,walk.durationSeconds,fetched,120_000L,walk.distanceMeters)
}

/** Access geometry is shared by trains at the same official platform, never by another
 * authority, station, line, or direction. Vehicle/service matching remains an ETA check.
 */
internal fun OfficialBoardingIdentity.sameBoardingAccessAs(other:OfficialBoardingIdentity):Boolean =
    isComplete && other.isComplete && authority==other.authority && stopId==other.stopId &&
        routeId==other.routeId && directionId==other.directionId &&
        (authority=="seoul" || serviceVariant==other.serviceVariant)

/** Distance and duration are one display unit, validated against the same location/source
 * that the movement calculator receives. Never display an orphan duration or distance.
 */
internal fun GuidanceWalkingRoute.displayMetrics(location:GuidanceLocation?,boarding:MapPoint?,now:Long):WalkRoute? {
    val fix=location?.takeIf {locationSampleFailure(it,now)==null} ?: return null
    val distance=distanceMeters ?: return null
    if(boarding==null || pedestrianDistance(destination,boarding)>15.0 || pedestrianDistance(origin,fix.point)>35.0 ||
        validForMillis<=0L || now-fetchedAtEpochMillis !in 0L..minOf(validForMillis,120_000L) ||
        distance<0 || durationSeconds<0 || (distance==0)!=(durationSeconds==0)) return null
    return WalkRoute(distance,durationSeconds)
}

/** Physical route progress can be displayed before an official vehicle identity is connected. */
internal fun JourneyScreenState.updateUnboundWalkingDisplay(fix:GuidanceLocation,now:Long) {
    if(confirmedServiceIdentity!=null || locationSampleFailure(fix,now)!=null ||
        location?.let {fix.observedAtEpochMillis<it.observedAtEpochMillis}==true) return
    location=fix
    val place=boarding ?: return
    val tracker=walkingTracker ?: return
    if(!validWalkingAccess(tracker.route,tracker.origin,place.point)) return
    val progress=tracker.update(fix,now) ?: return
    if(!progress.onRoute) {
        walkRoute=null;walkOrigin=null;walkFetchedAtEpochMillis=null;walkingPoints=emptyList()
        return
    }
    if(walkingCalculationDue(walkingCalculatedAtEpochMillis,now) || walkRoute==null ||
        progress.remainingMeters>(walkRoute?.distanceMeters ?: 0)) {
        walkRoute=WalkRoute(progress.remainingMeters,progress.providerSeconds)
        walkingCalculatedAtEpochMillis=now
    }
    walkOrigin=fix.point;walkFetchedAtEpochMillis=tracker.fetchedAt;walkingPoints=tracker.route.points
}
