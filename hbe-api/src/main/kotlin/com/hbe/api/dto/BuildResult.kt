package com.hbe.api.dto

data class BuildResult(
    val status: Status,
    val apkPath: String? = null,
    val apkSizeBytes: Long = 0,
    val phases: List<PhaseTiming> = emptyList(),
    val totalDurationMs: Long = 0,
    val ramPeakBytes: Long = 0,
    val cacheHits: Int = 0,
    val cacheMisses: Int = 0,
    val error: BuildError? = null,
    val buildId: String = "",
    val metadata: Map<String, Any> = emptyMap()
) {
    enum class Status {
        SUCCESS,
        FAILURE,
        CANCELLED
    }
}
