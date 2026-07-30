package com.hbe.api

import com.hbe.api.dto.ArtifactKey
import java.nio.file.Path

interface CacheManager {
    fun get(key: ArtifactKey): CacheResult?
    fun put(key: ArtifactKey, artifact: Path)
    fun invalidate(key: ArtifactKey)
    fun invalidateProject(projectId: String)
    fun evict(targetBytes: Long)
    fun cleanup(before: Long)
    fun stats(): CacheStats
}

data class CacheResult(
    val key: ArtifactKey,
    val artifactPath: Path,
    val contentHash: String,
    val sizeBytes: Long,
    val createdAt: Long,
    val accessCount: Int = 0
)

data class CacheStats(
    val totalEntries: Long = 0,
    val totalSizeBytes: Long = 0,
    val maxSizeBytes: Long = 0,
    val hitCount: Long = 0,
    val missCount: Long = 0,
    val evictionCount: Long = 0
) {
    val hitRate: Double
        get() {
            val total = hitCount + missCount
            return if (total == 0L) 0.0 else hitCount.toDouble() / total
        }
}
