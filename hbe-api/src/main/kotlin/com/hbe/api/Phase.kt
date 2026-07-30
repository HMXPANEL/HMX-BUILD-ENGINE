package com.hbe.api

import com.hbe.api.dto.PhaseTiming

interface Phase {
    val name: String
    fun execute(context: PhaseContext): PhaseResult
    fun getDependencies(): List<Class<out Phase>> = emptyList()
    fun isSkippable(context: PhaseContext): Boolean = false
    fun estimateMemoryMb(): Long = 256
}

data class PhaseResult(
    val status: PhaseTiming.PhaseStatus,
    val durationMs: Long = 0,
    val memoryPeakBytes: Long = 0,
    val error: PhaseError? = null
) {
    val isSuccess: Boolean get() = status == PhaseTiming.PhaseStatus.SUCCESS
    val isSkipped: Boolean get() = status == PhaseTiming.PhaseStatus.SKIPPED
    val isFailure: Boolean get() = status == PhaseTiming.PhaseStatus.FAILED
}

data class PhaseError(
    val code: String,
    val message: String,
    val suggestion: String? = null,
    val details: List<String> = emptyList()
)
