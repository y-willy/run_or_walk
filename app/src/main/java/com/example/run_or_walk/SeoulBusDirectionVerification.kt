package com.example.run_or_walk

import kotlinx.coroutines.CancellationException

/** Official route sequence remains authoritative; a failed lookup is not a confirmed direction. */
internal suspend fun verifySeoulBusBoardingDirection(repository:SeoulBusRepository,
    route:OfficialSeoulBusRoute,choice:KakaoTransitChoice,
    preloadedPath:List<OfficialSeoulBusRouteStop>?=null):Boolean {
    try {
        return seoulBusRouteReachesAlighting(route,choice,preloadedPath ?: repository.routeStops(route.routeId))
    } catch(cancelled:CancellationException) {throw cancelled}
    catch(error:SeoulBusApiException) {
        // A key for arrival/stop services may lack route-information approval. Never
        // retry that blocked service or bypass REQUEST_LIMIT/Retry-After. Query the
        // separately supported stop-route service, still using repository limits.
        if(error.failure!=SeoulBusFailure.ACCESS_DENIED || error.serverCode!="BUS_UPSTREAM_KEY_REJECTED") throw error
    }
    val alighting=choice.alightingStopName?.takeIf(String::isNotBlank)
        ?: choice.firstLegStopNames.lastOrNull()?.takeIf {normalizeBusStopName(it)!=normalizeBusStopName(choice.boardingStopName)}
        ?: return false
    val names=choice.firstLegStopNames.drop(1).ifEmpty {listOf(alighting)}
    if(names.size>16 || normalizeBusStopName(names.last())!=normalizeBusStopName(alighting)) return false
    var previousOrder=route.stopOrder.toIntOrNull() ?: return false
    if(!route.supported || previousOrder<=0) return false
    for(name in names) {
        val stops=repository.searchStops(name).filter {normalizeBusStopName(it.name)==normalizeBusStopName(name)}
            .distinctBy {it.stopId}
        if(stops.isEmpty() || stops.size>3) return false
        val candidates=stops.flatMap {stop -> repository.routes(stop).filter {
            it.supported && it.stopId==stop.stopId && it.routeId==route.routeId &&
                normalizeBusRouteName(it.name)==normalizeBusRouteName(route.name) &&
                it.stopOrder.toIntOrNull()?.let {order -> order>previousOrder}==true
        }}.distinctBy {it.stopId to it.stopOrder}
        val next=candidates.singleOrNull() ?: return false
        previousOrder=next.stopOrder.toInt()
    }
    return true
}
