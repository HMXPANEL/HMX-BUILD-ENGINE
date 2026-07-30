package com.hbe.cache

import com.hbe.api.*
import com.hbe.api.dto.ArtifactKey

class CacheManagerImpl(
    private val fileSystem: FileSystem,
    private val cacheDir: java.nio.file.Path,
    private val maxSizeBytes: Long = 2L * 1024 * 1024 * 1024
) : CacheManager {

    private val entryAccess = mutableMapOf<String, AccessRecord>()

    override fun get(key: ArtifactKey): CacheResult? {
        val cachePath = resolveCachePath(key)
        if (!fileSystem.exists(cachePath)) {
            return null
        }

        val meta = fileSystem.metadata(cachePath)
        entryAccess[key.toCachePath()] = AccessRecord(System.currentTimeMillis())

        return CacheResult(
            key = key,
            artifactPath = cachePath,
            contentHash = meta.sha256,
            sizeBytes = meta.size,
            createdAt = meta.lastModified,
            accessCount = accessCount(key)
        )
    }

    override fun put(key: ArtifactKey, artifact: java.nio.file.Path) {
        val cachePath = resolveCachePath(key)
        fileSystem.createDirectories(cachePath.parent)
        fileSystem.copy(artifact, cachePath)
        entryAccess[key.toCachePath()] = AccessRecord(System.currentTimeMillis())
    }

    override fun invalidate(key: ArtifactKey) {
        val cachePath = resolveCachePath(key)
        fileSystem.delete(cachePath)
        entryAccess.remove(key.toCachePath())
    }

    override fun invalidateProject(projectId: String) {
        // Remove entries matching project prefix
        entryAccess.keys
            .filter { it.startsWith(projectId) }
            .forEach { key ->
                val path = cacheDir.resolve(key)
                fileSystem.delete(path)
                entryAccess.remove(key)
            }
    }

    override fun evict(targetBytes: Long) {
        var currentSize = computeTotalSize()
        val sortedEntries = entryAccess.entries
            .sortedBy { it.value.lastAccessed }

        for ((key, _) in sortedEntries) {
            if (currentSize <= targetBytes) break
            val path = cacheDir.resolve(key)
            val size = fileSystem.size(path)
            fileSystem.delete(path)
            entryAccess.remove(key)
            currentSize -= size
        }
    }

    override fun cleanup(before: Long) {
        entryAccess.entries
            .filter { it.value.lastAccessed < before }
            .forEach { (key, _) ->
                val path = cacheDir.resolve(key)
                fileSystem.delete(path)
                entryAccess.remove(key)
            }
    }

    override fun stats(): CacheStats {
        return CacheStats(
            totalEntries = entryAccess.size.toLong(),
            totalSizeBytes = computeTotalSize(),
            maxSizeBytes = maxSizeBytes
        )
    }

    private fun resolveCachePath(key: ArtifactKey): java.nio.file.Path {
        return cacheDir.resolve(key.phase).resolve(key.toCachePath())
    }

    private fun accessCount(key: ArtifactKey): Int {
        val record = entryAccess[key.toCachePath()] ?: return 0
        return record.accessCount
    }

    private fun computeTotalSize(): Long {
        return entryAccess.keys.sumOf { key ->
            try {
                fileSystem.size(cacheDir.resolve(key))
            } catch (_: Exception) {
                0L
            }
        }
    }

    private data class AccessRecord(
        val lastAccessed: Long,
        val accessCount: Int = 1
    )
}
