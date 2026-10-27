package com.example.run_or_walk

/** UTC anchor plus monotonic elapsed time. UI ticks never rebase a received ETA.
 * Android callers supply elapsedRealtime (including sleep); tests supply a fake clock.
 * A new activity/service rechecks source timestamps against the current device clock.
 */
internal class GuidanceDisplayClock(private val epochAtStart:Long,private val elapsedAtStart:Long,
    private val elapsedNow:()->Long) {
    fun now():Long = epochAtStart+(elapsedNow()-elapsedAtStart).coerceAtLeast(0L)
}
