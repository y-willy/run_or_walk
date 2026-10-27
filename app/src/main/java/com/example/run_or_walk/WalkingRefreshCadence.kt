package com.example.run_or_walk

/** Calculation cadence and geometry validity are different. A new selection owns a new gate. */
internal const val WALKING_REFRESH_MILLIS=30_000L
internal class WalkingRefreshCadence {
    private var lastRequestElapsed:Long?=null
    val lastRequestedElapsed:Long? get()=lastRequestElapsed
    fun tryRequest(elapsed:Long):Boolean {
        if(lastRequestElapsed?.let { elapsed-it<WALKING_REFRESH_MILLIS }==true) return false
        lastRequestElapsed=elapsed;return true
    }
}
internal fun walkingCalculationDue(last:Long?,now:Long)=last==null || now-last>=WALKING_REFRESH_MILLIS
