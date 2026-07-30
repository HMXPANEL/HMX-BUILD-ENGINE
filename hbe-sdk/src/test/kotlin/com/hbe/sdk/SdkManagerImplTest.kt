package com.hbe.sdk

import com.hbe.api.*
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertContains

class SdkManagerImplTest {

    @TempDir
    lateinit var tempDir: Path

    private val logger: Logger = mockk(relaxed = true)
    private val processRunner: ProcessRunner = mockk()
    private val fileSystem: FileSystem = mockk(relaxed = true)

    // -----------------------------------------------------------------------
    // Version parsing — pure unit tests
    // -----------------------------------------------------------------------

    @Test
    fun `parseJavaVersion parses openjdk 17`() {
        val out = "openjdk version \"17.0.8\" 2023-07-18\nOpenJDK Runtime Environment (build 17.0.8+7)"
        val r = SdkManagerImpl.parseJavaVersion(out)
        assertNotNull(r)
        assertEquals(17, r!!.first)
    }

    @Test
    fun `parseJavaVersion parses openjdk 21`() {
        val r = SdkManagerImpl.parseJavaVersion("openjdk version \"21.0.2\" 2024-01-16")
        assertNotNull(r)
        assertEquals(21, r!!.first)
    }

    @Test
    fun `parseJavaVersion handles java 8 legacy format`() {
        val r = SdkManagerImpl.parseJavaVersion("java version \"1.8.0_202\"")
        assertNotNull(r)
        assertEquals(1, r!!.first)
    }

    @Test
    fun `parseJavaVersion returns null for empty`() {
        assertNull(SdkManagerImpl.parseJavaVersion(""))
    }

    @Test
    fun `parseJavacVersion succeeds`() {
        assertEquals("17.0.8", SdkManagerImpl.parseJavacVersion("javac 17.0.8"))
    }

    @Test
    fun `parseAapt2Version succeeds`() {
        val out = "Android Asset Packaging Tool (aapt) 2 version 8.5.0-10928562"
        assertEquals("8.5.0", SdkManagerImpl.parseAapt2Version(out))
    }

    @Test
    fun `parseD8Version succeeds`() {
        assertEquals("8.2.14", SdkManagerImpl.parseD8Version("D8 (desugar + d8) version 8.2.14"))
    }

    @Test
    fun `parseApksignerVersion succeeds`() {
        assertEquals("3.6.1", SdkManagerImpl.parseApksignerVersion("apksigner version 3.6.1"))
    }

    @Test
    fun `parseAdbVersion succeeds`() {
        val out = "Android Debug Bridge version 1.0.41\nVersion 34.0.5-10928562"
        assertEquals("1.0.41", SdkManagerImpl.parseAdbVersion(out))
    }

    @Test
    fun `parseAapt2Version returns null on garbage`() {
        assertNull(SdkManagerImpl.parseAapt2Version("command not found"))
    }

    // -----------------------------------------------------------------------
    // SDK root detection
    // -----------------------------------------------------------------------

    @Test
    fun `detects SDK from ANDROID_HOME`() {
        val sdkRoot = tempDir.resolve("sdk")
        Files.createDirectories(sdkRoot)
        val mgr = mgr(mapOf("ANDROID_HOME" to sdkRoot.toString()))
        assertEquals(sdkRoot.toAbsolutePath(), mgr.getSdkPath())
    }

    @Test
    fun `detects SDK from ANDROID_SDK_ROOT`() {
        val sdkRoot = tempDir.resolve("sdk")
        Files.createDirectories(sdkRoot)
        val mgr = mgr(mapOf("ANDROID_SDK_ROOT" to sdkRoot.toString()))
        assertEquals(sdkRoot.toAbsolutePath(), mgr.getSdkPath())
    }

    @Test
    fun `ANDROID_HOME takes priority over ANDROID_SDK_ROOT`() {
        val preferred = tempDir.resolve("preferred")
        val fallback = tempDir.resolve("fallback")
        Files.createDirectories(preferred)
        Files.createDirectories(fallback)
        val mgr = mgr(mapOf(
            "ANDROID_HOME" to preferred.toString(),
            "ANDROID_SDK_ROOT" to fallback.toString()
        ))
        assertEquals(preferred.toAbsolutePath(), mgr.getSdkPath())
    }

    @Test
    fun `returns null when no SDK env vars set`() {
        assertNull(mgr(emptyMap()).getSdkPath())
    }

    // -----------------------------------------------------------------------
    // JDK detection
    // -----------------------------------------------------------------------

    @Test
    fun `detects JDK from JAVA_HOME`() {
        val jdk = tempDir.resolve("jdk-17")
        Files.createDirectories(jdk)
        val mgr = mgr(mapOf("JAVA_HOME" to jdk.toString()))
        assertEquals(jdk.toAbsolutePath(), mgr.getJdkPath())
    }

    @Test
    fun `returns null when no JAVA_HOME and java not on PATH`() {
        val empty = tempDir.resolve("nope")
        Files.createDirectories(empty)
        assertNull(mgr(mapOf("PATH" to empty.toString())).getJdkPath())
    }

    @Test
    fun `detects JDK by walking up from java on PATH`() {
        val jdk = tempDir.resolve("jdk-17")
        val bin = jdk.resolve("bin")
        Files.createDirectories(bin)
        val java = bin.resolve("java")
        writeScript(java, "#!/bin/bash\necho java")
        val javac = bin.resolve("javac")
        writeScript(javac, "#!/bin/bash\necho javac")
        val mgr = mgr(mapOf("PATH" to bin.toString()))
        assertNotNull(mgr.getJdkPath())
    }

    // -----------------------------------------------------------------------
    // findTool
    // -----------------------------------------------------------------------

    @Test
    fun `findTool finds tool on PATH`() {
        val bin = tempDir.resolve("bin")
        Files.createDirectories(bin)
        val tool = bin.resolve("my-tool")
        writeScript(tool, "#!/bin/bash\necho hi")
        val mgr = mgr(mapOf("PATH" to bin.toString()))
        assertNotNull(mgr.findTool("my-tool"))
    }

    @Test
    fun `findTool returns null for nonexistent tool`() {
        val mgr = mgr(emptyMap())
        assertNull(mgr.findTool("this-tool-does-not-exist-42"))
    }

    @Test
    fun `findTool finds aapt2 in build-tools`() {
        val sdk = tempDir.resolve("sdk")
        val bt = sdk.resolve("build-tools").resolve("34.0.0")
        Files.createDirectories(bt)
        val aapt2 = bt.resolve("aapt2")
        writeScript(aapt2, "#!/bin/bash\necho aapt2")
        val mgr = mgr(mapOf("ANDROID_HOME" to sdk.toString()))
        assertNotNull(mgr.findTool("aapt2"))
    }

    @Test
    fun `findTool finds adb in platform-tools`() {
        val sdk = tempDir.resolve("sdk")
        val pt = sdk.resolve("platform-tools")
        Files.createDirectories(pt)
        val adb = pt.resolve("adb")
        writeScript(adb, "#!/bin/bash\necho adb")
        val mgr = mgr(mapOf("ANDROID_HOME" to sdk.toString()))
        assertNotNull(mgr.findTool("adb"))
    }

    @Test
    fun `findTool finds java in JAVA_HOME bin`() {
        val jdk = tempDir.resolve("jdk")
        val bin = jdk.resolve("bin")
        Files.createDirectories(bin)
        val java = bin.resolve("java")
        writeScript(java, "#!/bin/bash\necho java")
        val mgr = mgr(mapOf("JAVA_HOME" to jdk.toString()))
        val found = mgr.findTool("java")
        assertNotNull(found)
        assertTrue(found!!.toString().contains("jdk"))
    }

    // -----------------------------------------------------------------------
    // listInstalledSdk
    // -----------------------------------------------------------------------

    @Test
    fun `listInstalledSdk returns platforms and build-tools`() {
        val sdk = tempDir.resolve("sdk")
        for (api in listOf(34, 33, 30)) {
            Files.createDirectories(sdk.resolve("platforms").resolve("android-$api"))
        }
        for (bt in listOf("34.0.0", "33.0.2")) {
            Files.createDirectories(sdk.resolve("build-tools").resolve(bt))
        }
        val mgr = mgr(mapOf("ANDROID_HOME" to sdk.toString()))
        val info = mgr.listInstalledSdk()
        assertEquals(listOf(30, 33, 34), info.platforms)
        assertEquals(listOf("34.0.0", "33.0.2"), info.buildToolsVersions)
        assertFalse(info.platformToolsAvailable)
    }

    @Test
    fun `listInstalledSdk detects platform-tools and ndk`() {
        val sdk = tempDir.resolve("sdk")
        Files.createDirectories(sdk.resolve("platform-tools"))
        Files.createDirectories(sdk.resolve("ndk").resolve("26.1.10909125"))
        val mgr = mgr(mapOf("ANDROID_HOME" to sdk.toString()))
        val info = mgr.listInstalledSdk()
        assertTrue(info.platformToolsAvailable)
        assertTrue(info.ndkAvailable)
    }

    @Test
    fun `listInstalledSdk returns empty when SDK not found`() {
        val info = mgr(emptyMap()).listInstalledSdk()
        assertNull(info.sdkRoot)
        assertTrue(info.platforms.isEmpty())
        assertTrue(info.buildToolsVersions.isEmpty())
    }

    // -----------------------------------------------------------------------
    // listInstalledTools
    // -----------------------------------------------------------------------

    @Test
    fun `listInstalledTools returns 7 entries`() {
        val sdk = tempDir.resolve("sdk")
        Files.createDirectories(sdk.resolve("build-tools").resolve("34.0.0"))
        Files.createDirectories(sdk.resolve("platform-tools"))
        val bin = tempDir.resolve("bin")
        Files.createDirectories(bin)
        val java = bin.resolve("java")
        writeScript(java, "#!/bin/bash\necho java")

        every {
            processRunner.run(any(), listOf("-version"))
        } returns ProcessResult(0, "", "openjdk version \"17.0.8\" 2023-07-18", 10)
        every {
            processRunner.run(any(), listOf("version"))
        } returns ProcessResult(0, "apksigner version 3.6.1", "", 10)
        every {
            processRunner.run(any(), listOf("--version"))
        } returns ProcessResult(0, "D8 (desugar + d8) version 8.2.14", "", 10)

        val mgr = mgr(mapOf("PATH" to bin.toString(), "ANDROID_HOME" to sdk.toString()))
        val tools = mgr.listInstalledTools()
        assertEquals(7, tools.size)
    }

    // -----------------------------------------------------------------------
    // validateEnvironment
    // -----------------------------------------------------------------------

    @Test
    fun `validateEnvironment detects missing SDK and JDK`() {
        every {
            processRunner.run(any(), listOf("-version"))
        } returns ProcessResult(0, "", "openjdk version \"17.0.8\" 2023-07-18", 10)
        val mgr = mgr(emptyMap())
        val r = mgr.validateEnvironment()
        assertNull(r.androidSdk?.sdkRoot)
    }

    @Test
    fun `validateEnvironment detects GitHub Actions`() {
        val env = mapOf("GITHUB_ACTIONS" to "true")
        assertTrue(mgr(env).validateEnvironment().isGitHubActions)
    }

    @Test
    fun `validateEnvironment reports missing java`() {
        val mgr = mgr(emptyMap())
        val r = mgr.validateEnvironment()
        assertFalse(r.java.available)
        assertTrue(r.errors.any { it.contains("Java") })
    }

    @Test
    fun `validateEnvironment with complete setup`() {
        val sdk = tempDir.resolve("sdk")
        Files.createDirectories(sdk.resolve("build-tools").resolve("34.0.0"))
        Files.createDirectories(sdk.resolve("platform-tools"))
        val bin = tempDir.resolve("bin")
        Files.createDirectories(bin)
        val java = bin.resolve("java")
        writeScript(java, "#!/bin/bash\necho java")

        every {
            processRunner.run(any(), listOf("-version"))
        } returns ProcessResult(0, "", "openjdk version \"21.0.2\" 2024-01-16", 10)
        every {
            processRunner.run(any(), listOf("version"))
        } returns ProcessResult(0, "apksigner version 3.6.1", "", 10)
        every {
            processRunner.run(any(), listOf("--version"))
        } returns ProcessResult(0, "D8 (desugar + d8) version 8.2.14", "", 10)

        val mgr = mgr(mapOf("PATH" to bin.toString(), "ANDROID_HOME" to sdk.toString()))
        val r = mgr.validateEnvironment()
        assertTrue(r.errors.isEmpty())
        assertEquals("21.0.2", r.java.version)
    }

    // -----------------------------------------------------------------------
    // doctor
    // -----------------------------------------------------------------------

    @Test
    fun `doctor reports missing SDK`() {
        val mgr = mgr(emptyMap())
        val d = mgr.doctor()
        assertFalse(d.sdkFound)
    }

    // -----------------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------------

    private fun mgr(env: Map<String, String>): SdkManagerImpl =
        SdkManagerImpl(fileSystem, processRunner, logger, env)

    private fun writeScript(path: Path, content: String) {
        Files.writeString(path, content)
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwxr-xr-x"))
        } catch (_: UnsupportedOperationException) {
            path.toFile().setExecutable(true)
        }
    }
}
