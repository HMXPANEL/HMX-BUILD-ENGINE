package com.hbe.dex

import com.hbe.api.*
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DexEngineImplTest {

    @TempDir
    lateinit var tempDir: Path

    private val logger = com.hbe.core.DefaultLogger()

    private fun minimalDexBytes(methodIds: Int, fieldIds: Int): ByteArray {
        val bytes = ByteArray(112)
        val magic = "dex\n035\u0000".toByteArray()
        magic.copyInto(bytes, 0)
        leWriteInt(bytes, 84, fieldIds)
        leWriteInt(bytes, 92, methodIds)
        return bytes
    }

    private fun leWriteInt(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value and 0xFF).toByte()
        bytes[offset + 1] = ((value shr 8) and 0xFF).toByte()
        bytes[offset + 2] = ((value shr 16) and 0xFF).toByte()
        bytes[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }

    private fun engine(): DexEngineImpl {
        val sdkManager = mockk<SdkManager>()
        return DexEngineImpl(sdkManager, com.hbe.infra.OsFileSystem(), mockk(), logger)
    }

    @Test
    fun `parseDexHeader reads method and field counts`() {
        val dexFile = tempDir.resolve("classes.dex")
        Files.write(dexFile, minimalDexBytes(methodIds = 1234, fieldIds = 567))

        val (methods, fields) = engine().parseDexHeader(dexFile)

        assertEquals(1234, methods)
        assertEquals(567, fields)
    }

    @Test
    fun `parseDexHeader returns zeros for invalid file`() {
        val dexFile = tempDir.resolve("classes.dex")
        Files.write(dexFile, "not a dex".toByteArray())

        val (methods, fields) = engine().parseDexHeader(dexFile)

        assertEquals(0, methods)
        assertEquals(0, fields)
    }

    @Test
    fun `dex runs d8 and reports method counts`() {
        val toolRunner = mockk<ToolRunner>()
        every { toolRunner.run("d8", any(), any()) } answers {
            val args = secondArg<List<String>>()
            val outputDir = Path.of(args[args.indexOf("--output") + 1])
            Files.createDirectories(outputDir)
            Files.write(outputDir.resolve("classes.dex"), minimalDexBytes(methodIds = 42, fieldIds = 7))
            ToolResult(0, "", "", 25, true)
        }
        val engine = DexEngineImpl(mockk(), com.hbe.infra.OsFileSystem(), toolRunner, logger)
        val classFile = tempDir.resolve("MainActivity.class")
        Files.write(classFile, byteArrayOf(1, 2, 3))
        val androidJar = tempDir.resolve("android.jar")
        val outputDir = tempDir.resolve("dex")

        val result = engine.dex(
            classFiles = setOf(classFile),
            config = DexConfig(minSdk = 24, debug = true, outputDir = outputDir, libraryJars = listOf(androidJar))
        )

        assertEquals(listOf(outputDir.resolve("classes.dex")), result.dexFiles)
        assertEquals(42, result.totalMethodCount)
        assertEquals(7, result.totalFieldCount)
        assertEquals(1, result.dexFileCount)
        verify {
            toolRunner.run("d8", match {
                it.contains("--debug") && it.contains("--min-api") &&
                    it.contains("24") && it.contains("--lib") &&
                    it.contains(androidJar.toString()) && it.contains(classFile.toString())
            }, any())
        }
    }

    @Test
    fun `dex with no class files throws`() {
        val engine = engine()
        assertThrows(com.hbe.api.exception.DexException::class.java) {
            engine.dex(emptySet(), DexConfig(outputDir = tempDir.resolve("dex")))
        }
    }

    @Test
    fun `computeMethodCount returns dex method count`() {
        val toolRunner = mockk<ToolRunner>()
        every { toolRunner.run("d8", any(), any()) } answers {
            val args = secondArg<List<String>>()
            val outputDir = Path.of(args[args.indexOf("--output") + 1])
            Files.createDirectories(outputDir)
            Files.write(outputDir.resolve("classes.dex"), minimalDexBytes(methodIds = 777, fieldIds = 1))
            ToolResult(0, "", "", 25, true)
        }
        val engine = DexEngineImpl(mockk(), com.hbe.infra.OsFileSystem(), toolRunner, logger)
        val classFile = tempDir.resolve("App.class")
        Files.write(classFile, byteArrayOf(1))

        val count = engine.computeMethodCount(setOf(classFile))

        assertEquals(777, count)
    }
}
