package com.example.run_or_walk

/**
 * A route selection itself selects the route provider's first boarding stop.
 * This is an estimated pin, not evidence of an official stop/platform identity.
 * The vehicle match is independently verified before WALK/FAST_WALK guidance.
 */
internal fun routeBoardingPlace(route: JourneyTransitRoute): JourneyPlace? {
    val choice = route.kakao
    if (choice.firstTransitType !in setOf("BUS", "SUBWAY")) return null
    val name = choice.boardingStopName.trim().takeIf(String::isNotEmpty) ?: return null
    val point = choice.boardingPoint ?: return null
    if (!point.x.isFinite() || !point.y.isFinite() ||
        point.x !in -180.0..180.0 || point.y !in -90.0..90.0) return null
    return JourneyPlace("route-boarding-${route.id}", name,
        "경로 제공 첫 승차 지점 · 승강장 위치는 현장에서 확인", point)
}
