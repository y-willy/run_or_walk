package com.example.run_or_walk

internal enum class JourneyMonitoringAlertKind { GUIDANCE_CHANGED, ARRIVING }
internal data class JourneyMonitoringAlert(val kind: JourneyMonitoringAlertKind, val status: AutoGuidanceStatus)

/** A changing countdown never causes repeated alerts. An actual vehicle gets one approach alert. */
internal class JourneyMonitoringAlertGate {
    private var guidanceState = AutoVibrationState()
    private val approachedVehicles = LinkedHashSet<Pair<OfficialBoardingIdentity, String>>()

    fun claim(identity: OfficialBoardingIdentity, vehicleId: String?, assessment: AutoGuidanceAssessment,
        arrivals: RealtimeArrivalSnapshot?, now: Long): JourneyMonitoringAlert? {
        val usable = arrivals != null && vehicleId?.isNotBlank() == true && arrivals.identity == identity &&
            arrivals.reliable && arrivals.trustState == ArrivalTrustState.VALID && arrivals.validForMillis > 0L &&
            now - arrivals.fetchedAtEpochMillis in 0L..minOf(arrivals.validForMillis, 60_000L) &&
            arrivals.firstArrivalAtEpochMillis > now
        val decision = decideAutoGuidanceVibration(guidanceState, usable, identity, assessment.status,
            now, vehicleId = vehicleId)
        guidanceState = decision.state
        if (usable && arrivals.firstArrivalAtEpochMillis - now in 1L..60_000L) {
            val key = identity to vehicleId
            if (approachedVehicles.add(key)) {
                if (approachedVehicles.size > 64) approachedVehicles.remove(approachedVehicles.first())
                // This is arrival information; UNKNOWN never turns it into a catch recommendation.
                return JourneyMonitoringAlert(JourneyMonitoringAlertKind.ARRIVING, assessment.status)
            }
        }
        return if (decision.shouldVibrate) JourneyMonitoringAlert(JourneyMonitoringAlertKind.GUIDANCE_CHANGED,
            assessment.status) else null
    }
}

/** A notification cannot outlive either its source observation or the vehicle it describes. */
internal fun monitoringAlertLifetimeMillis(
    alert: JourneyMonitoringAlert,
    arrivals: RealtimeArrivalSnapshot,
    now: Long
): Long {
    val target = if (alert.kind == JourneyMonitoringAlertKind.GUIDANCE_CHANGED &&
        alert.status == AutoGuidanceStatus.NEXT_VEHICLE) arrivals.nextArrivalAtEpochMillis
        ?: arrivals.firstArrivalAtEpochMillis else arrivals.firstArrivalAtEpochMillis
    val sourceRemaining = minOf(arrivals.validForMillis, 60_000L) - (now - arrivals.fetchedAtEpochMillis)
    return minOf(sourceRemaining, target - now).coerceIn(0L, 60_000L)
}
