package com.hbe.sdk

import com.hbe.api.*
import com.hbe.api.exception.SdkException
import io.mockk.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SdkInstallerImplTest {

    @TempDir
    lateinit var tempDir: Path

    private val networkClient: NetworkClient = mockk(relaxed = true)
    private val fileSystem: FileSystem = mockk(relaxed = true)
    private val logger: Logger = mockk(relaxed = true)

    @Test
    fun `platformDownloadUrl constructs correct URL`() {
        assertEquals("https://dl.google.com/android/repository/platform-34_r01.zip",
            SdkInstallerImpl.platformDownloadUrl(34, "linux"))
    }

    @Test
    fun `buildToolsDownloadUrl constructs correct URL`() {
        assertEquals("https://dl.google.com/android/repository/build-tools_r34.0.0-linux.zip",
            SdkInstallerImpl.buildToolsDownloadUrl("34.0.0", "linux"))
    }

    @Test
    fun `computeSha256 matches known value`() {
        val file = tempDir.resolve("test.bin")
        Files.write(file, byteArrayOf(1, 2, 3, 4, 5))
        val expected = MessageDigest.getInstance("SHA-256").digest(byteArrayOf(1, 2, 3, 4, 5))
            .joinToString("") { "%02x".format(it) }
        assertEquals(expected, makeInstaller().computeSha256(file))
    }

    @Test
    fun `computeSha256 for empty file`() {
        val file = tempDir.resolve("empty.bin")
        Files.write(file, ByteArray(0))
        val expected = MessageDigest.getInstance("SHA-256").digest(ByteArray(0))
            .joinToString("") { "%02x".format(it) }
        assertEquals(expected, makeInstaller().computeSha256(file))
    }

    @Test
    fun `computeSha256 for large content`() {
        val file = tempDir.resolve("large.bin")
        val data = ByteArray(65537) { (it % 256).toByte() }
        Files.write(file, data)
        val expected = MessageDigest.getInstance("SHA-256").digest(data)
            .joinToString("") { "%02x".format(it) }
        assertEquals(expected, makeInstaller().computeSha256(file))
    }

    @Test
    fun `checkCancelled does nothing when token not cancelled`() {
        val token = mockk<CancellationToken>(relaxed = true)
        every { token.isCancelled } returns false
        makeInstaller().checkCancelled(token) // should not throw
    }

    @Test
    fun `checkCancelled throws when token cancelled`() {
        val token = mockk<CancellationToken>(relaxed = true)
        every { token.isCancelled } returns true
        assertThrows(SdkException::class.java) { makeInstaller().checkCancelled(token) }
    }

    @Test
    fun `checkCancelled does nothing when token null`() {
        makeInstaller().checkCancelled(null) // should not throw
    }

    @Test
    fun `getInstalledPlatforms empty initially`() {
        assertTrue(makeInstaller().getInstalledPlatforms().isEmpty())
    }

    @Test
    fun `getInstalledBuildTools empty initially`() {
        assertTrue(makeInstaller().getInstalledBuildTools().isEmpty())
    }

    @Test
    fun `getCacheSize returns 0 initially`() {
        assertEquals(0, makeInstaller().getCacheSize())
    }

    @Test
    fun `install preinstalled platform returns fromCache`() {
        val sdkRoot = tempDir.resolve("sdk")
        Files.createDirectories(sdkRoot.resolve("platforms").resolve("android-34"))
        val installer = SdkInstallerImpl(networkClient, fileSystem, logger,
            SdkInstallOptions(sdkRoot = sdkRoot, cacheDir = tempDir.resolve("cache")))
        val result = installer.installPlatform(34)
        assertTrue(result.success)
        assertTrue(result.fromCache)
    }

    @Test
    fun `install preinstalled build-tools returns fromCache`() {
        val sdkRoot = tempDir.resolve("sdk")
        Files.createDirectories(sdkRoot.resolve("build-tools").resolve("34.0.0"))
        val installer = SdkInstallerImpl(networkClient, fileSystem, logger,
            SdkInstallOptions(sdkRoot = sdkRoot, cacheDir = tempDir.resolve("cache")))
        val result = installer.installBuildTools("34.0.0")
        assertTrue(result.success)
        assertTrue(result.fromCache)
    }

    @Test
    fun `SHA-256 mismatch fails installation`() {
        val sdkRoot = tempDir.resolve("sdk")
        val cacheDir = tempDir.resolve("cache")
        every { networkClient.get(any()) } returns HttpResponse(
            statusCode = 200,
            body = "1111111111111111111111111111111111111111111111111111111111111111".toByteArray()
        )
        val installer = makeFakeDownloader(
            SdkInstallOptions(sdkRoot = sdkRoot, cacheDir = cacheDir, verifySha256 = true),
            zipContent = mapOf("c.txt" to "data"),
            expectedSha256 = "0000000000000000000000000000000000000000000000000000000000000000"
        )
        assertThrows(SdkException::class.java) { installer.installPlatform(33) }
    }

    @Test
    fun `progress callback invoked during install`() {
        val sdkRoot = tempDir.resolve("sdk")
        val cacheDir = tempDir.resolve("cache")
        val installer = makeFakeDownloader(
            SdkInstallOptions(sdkRoot = sdkRoot, cacheDir = cacheDir, verifySha256 = false),
            zipContent = mapOf("f.txt" to "data")
        )
        var called = false
        installer.installPlatform(33, callback = ProgressCallback { _, _ -> called = true })
        assertTrue(called)
    }

    @Test
    fun `cancellation during install throws`() {
        val token = mockk<CancellationToken>(relaxed = true)
        every { token.isCancelled } returns true
        val installer = SdkInstallerImpl(networkClient, fileSystem, logger,
            SdkInstallOptions(sdkRoot = tempDir.resolve("sdk"), cacheDir = tempDir.resolve("cache")))
        assertThrows(SdkException::class.java) { installer.installPlatform(33, token = token) }
    }

    @Test
    fun `clearCache removes cached files`() {
        val cacheDir = tempDir.resolve("cache")
        Files.createDirectories(cacheDir.resolve("downloads"))
        Files.writeString(cacheDir.resolve("downloads").resolve("dummy.zip"), "data")
        val installer = SdkInstallerImpl(networkClient, fileSystem, logger,
            SdkInstallOptions(sdkRoot = tempDir.resolve("sdk"), cacheDir = cacheDir))
        assertTrue(installer.getCacheSize() > 0)
        installer.clearCache()
        assertEquals(0, installer.getCacheSize())
    }

    @Test
    fun `clearCache creates dir on nonexistent cache`() {
        val cacheDir = tempDir.resolve("nonexistent")
        val installer = SdkInstallerImpl(networkClient, fileSystem, logger,
            SdkInstallOptions(sdkRoot = tempDir.resolve("sdk"), cacheDir = cacheDir))
        installer.clearCache()
        assertTrue(Files.isDirectory(cacheDir))
    }

    @Test
    fun `getCacheSize after cache created`() {
        val cacheDir = tempDir.resolve("cache")
        val installer = SdkInstallerImpl(networkClient, fileSystem, logger,
            SdkInstallOptions(sdkRoot = tempDir.resolve("sdk"), cacheDir = cacheDir))
        assertEquals(0, installer.getCacheSize())
        Files.writeString(cacheDir.resolve("downloads").resolve("x.zip"), "hello")
        assertTrue(installer.getCacheSize() > 0)
    }

    private fun makeInstaller(): SdkInstallerImpl {
        return SdkInstallerImpl(networkClient, fileSystem, logger,
            SdkInstallOptions(
                sdkRoot = tempDir.resolve("sdk"),
                cacheDir = tempDir.resolve("cache"),
                verifySha256 = false
            ))
    }

    private fun makeFakeDownloader(
        opts: SdkInstallOptions,
        zipContent: Map<String, String>,
        expectedSha256: String? = null
    ): SdkInstallerImpl {
        return object : SdkInstallerImpl(networkClient, fileSystem, logger, opts) {
            override fun downloadWithResume(
                url: String, destination: Path,
                callback: ProgressCallback?, token: CancellationToken?
            ): DownloadTransfer {
                createTestZip(destination, zipContent)
                return DownloadTransfer(Files.size(destination), false)
            }

            override fun computeSha256(path: Path): String {
                return expectedSha256 ?: super.computeSha256(path)
            }
        }
    }

    private fun createTestZip(zipPath: Path, entries: Map<String, String>) {
        Files.createDirectories(zipPath.parent)
        ZipOutputStream(Files.newOutputStream(zipPath)).use { zos ->
            for ((name, content) in entries) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(content.toByteArray())
                zos.closeEntry()
            }
        }
    }
}
