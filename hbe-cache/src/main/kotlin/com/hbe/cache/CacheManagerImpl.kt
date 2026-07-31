package com.hbe.cache

import com.hbe.api.*
import com.hbe.api.dto.ArtifactKey

class CacheManagerImpl(
    private val fileSystem: FileSystem,
    private val cacheDir: java.nio.file.Path,
    private val maxSizeBytes: Long = 2L * 1024 * 1024 * 1024
) : CacheManager {

    private val entryAccess = java.util.concurrent.ConcurrentHashMap<String, AccessRecord>()
    private val hitCounter = java.util.concurrent.atomic.AtomicLong()
    private val missCounter = java.util.concurrent.atomic.AtomicLong()
    private val evictionCounter = java.util.concurrent.atomic.AtomicLong()

    override fun get(key: ArtifactKey): CacheResult? {
        val cachePath = resolveCachePath(key)
        if (!fileSystem.exists(cachePath)) {
            missCounter.incrementAndGet()
            return null
        }

        val meta = fileSystem.metadata(cachePath)
        entryAccess[key.toCachePath()] = AccessRecord(System.currentTimeMillis())
        hitCounter.incrementAndGet()

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
            evictionCounter.incrementAndGet()
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
                evictionCounter.incrementAndGet()
            }
    }

    override fun stats(): CacheStats {
        return CacheStats(
            totalEntries = entryAccess.size.toLong(),
            totalSizeBytes = computeTotalSize(),
            maxSizeBytes = maxSizeBytes,
            hitCount = hitCounter.get(),
            missCount = missCounter.get(),
            evictionCount = evictionCounter.get()
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
