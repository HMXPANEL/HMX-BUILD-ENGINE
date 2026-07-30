package com.hbe.api

interface RecoverySystem {
    fun checkpoint(buildContext: BuildContext)
    fun getLastCheckpoint(projectId: String): BuildContext?
    fun clearCheckpoints(projectId: String)
    fun isRecoveryAvailable(projectId: String): Boolean
    fun verifyCache(cacheManager: CacheManager): CacheVerificationReport
}

data class CacheVerificationReport(
    val entriesChecked: Int = 0,
    val corruptedEntries: Int = 0,
    val corruptedRate: Double = 0.0,
    val isHealthy: Boolean = true,
    val recommendations: List<String> = emptyList()
)
