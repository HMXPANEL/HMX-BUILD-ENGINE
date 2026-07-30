package com.hbe.api.dto

data class PhaseTiming(
    val name: String,
    val status: PhaseStatus,
    val durationMs: Long = 0,
    val ramPeakBytes: Long = 0,
    val inputCount: Int = 0,
    val cacheHit: Boolean = false
) {
    enum class PhaseStatus {
        SUCCESS,
        SKIPPED,
        FAILED,
        CANCELLED
    }
}
