package com.example.run_or_walk

/** A snapshot of the manual arrival estimate and walking time used for guidance. */
data class UrgencyAssessment(
    val urgency: Urgency,
    val arrivalMinutes: Double,
    val walkingMinutes: Double,
    val bufferMinutes: Double,
    val slackMinutes: Double
)

/** Keeps the original urgency thresholds and second-level walking precision. */
fun calculateUrgency(
    arrivalMinutes: Double,
    bufferMinutes: Double,
    walkingSeconds: Int
): UrgencyAssessment {
    require(arrivalMinutes.isFinite() && arrivalMinutes >= 0) {
        "차량 도착시간을 올바르게 입력하세요."
    }
    require(bufferMinutes.isFinite() && bufferMinutes >= 0) {
        "안전 여유시간을 올바르게 입력하세요."
    }
    require(walkingSeconds >= 0) {
        "도보시간을 확인하세요."
    }

    val walkingMinutes = walkingSeconds / 60.0
    val slackMinutes = arrivalMinutes - walkingMinutes - bufferMinutes
    val urgency = when {
        slackMinutes >= 3 -> Urgency.NONE
        slackMinutes >= 1 -> Urgency.LOW
        slackMinutes >= 0 -> Urgency.MEDIUM
        slackMinutes >= -2 -> Urgency.HIGH
        else -> Urgency.MISSED
    }

    return UrgencyAssessment(
        urgency = urgency,
        arrivalMinutes = arrivalMinutes,
        walkingMinutes = walkingMinutes,
        bufferMinutes = bufferMinutes,
        slackMinutes = slackMinutes
    )
}
