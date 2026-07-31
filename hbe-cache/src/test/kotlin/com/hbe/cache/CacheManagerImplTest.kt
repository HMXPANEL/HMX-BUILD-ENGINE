package com.hbe.cache

import com.hbe.api.dto.ArtifactKey
import com.hbe.infra.OsFileSystem
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class CacheManagerImplTest {

    @TempDir
    lateinit var tempDir: Path

    private fun key(inputHash: String = "abc123") = ArtifactKey(
        phase = "JAVA_COMPILE",
        projectId = "com.example:app",
        inputHash = inputHash,
        toolVersion = "35.0.0",
        variant = "debug"
    )

    @Test
    fun `get returns null on miss and counts miss`() {
        val cache = CacheManagerImpl(OsFileSystem(), tempDir.resolve("cache"))

        assertNull(cache.get(key()))
        assertEquals(0, cache.stats().hitCount)
        assertEquals(1, cache.stats().missCount)
    }

    @Test
    fun `put then get returns the artifact and counts hit`() {
        val cache = CacheManagerImpl(OsFileSystem(), tempDir.resolve("cache"))
        val artifact = tempDir.resolve("out/classes.dex")
        Files.createDirectories(artifact.parent)
        Files.write(artifact, byteArrayOf(1, 2, 3, 4))

        cache.put(key(), artifact)
        val result = cache.get(key())

        assertNotNull(result)
        assertEquals(4, result!!.sizeBytes)
        assertTrue(Files.isRegularFile(result.artifactPath))
        assertEquals(1, cache.stats().hitCount)
        assertEquals(0, cache.stats().missCount)
        assertEquals(1L, cache.stats().totalEntries)
    }

    @Test
    fun `different input hash misses`() {
        val cache = CacheManagerImpl(OsFileSystem(), tempDir.resolve("cache"))
        val artifact = tempDir.resolve("out/a.bin")
        Files.createDirectories(artifact.parent)
        Files.write(artifact, byteArrayOf(9))

        cache.put(key("hash-one"), artifact)

        assertNotNull(cache.get(key("hash-one")))
        assertNull(cache.get(key("hash-two")))
        assertEquals(1, cache.stats().hitCount)
        assertEquals(1, cache.stats().missCount)
    }

    @Test
    fun `invalidate removes entry`() {
        val cache = CacheManagerImpl(OsFileSystem(), tempDir.resolve("cache"))
        val artifact = tempDir.resolve("out/a.bin")
        Files.createDirectories(artifact.parent)
        Files.write(artifact, byteArrayOf(9))

        cache.put(key(), artifact)
        cache.invalidate(key())

        assertNull(cache.get(key()))
        assertEquals(0L, cache.stats().totalEntries)
    }

    @Test
    fun `hit rate computed from counters`() {
        val cache = CacheManagerImpl(OsFileSystem(), tempDir.resolve("cache"))
        val artifact = tempDir.resolve("out/a.bin")
        Files.createDirectories(artifact.parent)
        Files.write(artifact, byteArrayOf(9))

        cache.put(key(), artifact)
        cache.get(key())
        cache.get(key())
        cache.get(key("other"))

        assertEquals(2L, cache.stats().hitCount)
        assertEquals(1L, cache.stats().missCount)
        assertEquals(2.0 / 3.0, cache.stats().hitRate)
    }
}
