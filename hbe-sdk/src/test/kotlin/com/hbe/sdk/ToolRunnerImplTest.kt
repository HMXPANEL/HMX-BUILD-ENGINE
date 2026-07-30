package com.hbe.sdk

import com.hbe.api.*
import io.mockk.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ToolRunnerImplTest {

    @TempDir
    lateinit var tempDir: Path

    private val sdkManager: SdkManager = mockk(relaxed = true)
    private val processRunner: ProcessRunner = mockk()
    private val logger: Logger = mockk(relaxed = true)

    private fun runner(): ToolRunnerImpl = ToolRunnerImpl(sdkManager, processRunner, logger)

    @Test
    fun `run returns tool not found error when tool missing`() {
        every { sdkManager.findTool(any()) } returns null
        every { processRunner.findTool(any()) } returns null

        val result = runner().run("aapt2", listOf("version"))
        assertFalse(result.succeeded)
        assertNotNull(result.error)
        assertEquals(com.hbe.api.ToolErrorType.TOOL_NOT_FOUND, result.error!!.type)
    }

    @Test
    fun `run returns cancelled error when token already cancelled`() {
        val token = mockk<CancellationToken>(relaxed = true)
        every { token.isCancelled } returns true

        val result = runner().run("java", emptyList(), ToolOptions(cancellationToken = token))
        assertFalse(result.succeeded)
        assertNotNull(result.error)
        assertEquals(com.hbe.api.ToolErrorType.CANCELLED, result.error!!.type)
    }

    @Test
    fun `run succeeds with valid tool and args`() {
        val toolPath = tempDir.resolve("fake-aapt2")
        Files.writeString(toolPath, "#!/bin/bash\necho version 8.5.0")
        toolPath.toFile().setExecutable(true)

        every { sdkManager.findTool("aapt2") } returns toolPath
        every { processRunner.runWithTimeout(any(), any(), any(), any()) } returns
            ProcessResult(0, "Android Asset Packaging Tool (aapt) 2 version 8.5.0-10928562", "", 50)

        val result = runner().run("aapt2", listOf("version"))
        assertTrue(result.succeeded)
        assertEquals(0, result.exitCode)
    }

    @Test
    fun `run captures non-zero exit code as error`() {
        every { sdkManager.findTool(any()) } returns Path.of("/usr/bin/java")
        every { processRunner.runWithTimeout(any(), any(), any(), any()) } returns
            ProcessResult(1, "", "error: file not found", 30)

        val result = runner().run("javac", listOf("nonexistent.java"))
        assertFalse(result.succeeded)
        assertEquals(1, result.exitCode)
        assertNotNull(result.error)
        assertEquals(com.hbe.api.ToolErrorType.EXECUTION_ERROR, result.error!!.type)
    }

    @Test
    fun `run propagates timeout error`() {
        every { sdkManager.findTool(any()) } returns Path.of("/usr/bin/java")
        every { processRunner.runWithTimeout(any(), any(), any(), any()) } returns
            ProcessResult(-1, "", "Process timed out after 100ms", 100)
            .also { every { processRunner.runWithTimeout(any(), any(), any(), any()) } returns it }

        val result = runner().run("java", listOf("-version"), ToolOptions(timeoutMs = 100))
        assertFalse(result.succeeded)
        assertEquals(com.hbe.api.ToolErrorType.TIMEOUT, result.error!!.type)
    }

    @Test
    fun `run passes working directory to process`() {
        val workingDir = tempDir.resolve("workdir")
        Files.createDirectories(workingDir)

        every { sdkManager.findTool(any()) } returns Path.of("/usr/bin/javac")
        every { processRunner.runWithTimeout(any(), any(), any(), withArg { config ->
            assertNotNull(config.workingDir)
            assertTrue(config.workingDir!!.toString().contains("workdir"))
        }) } returns ProcessResult(0, "", "", 10)

        runner().run("javac", listOf("-version"), ToolOptions(workingDir = workingDir))
        verify { processRunner.runWithTimeout(any(), any(), any(), any()) }
    }

    @Test
    fun `run passes environment variables to process`() {
        val env = mapOf("MY_VAR" to "hello", "PATH" to "/custom")

        every { sdkManager.findTool(any()) } returns Path.of("/usr/bin/javac")
        every { processRunner.runWithTimeout(any(), any(), any(), withArg { config ->
            assertEquals("hello", config.environment["MY_VAR"])
            assertEquals("/custom", config.environment["PATH"])
        }) } returns ProcessResult(0, "", "", 10)

        runner().run("javac", listOf(), ToolOptions(environment = env))
        verify { processRunner.runWithTimeout(any(), any(), any(), any()) }
    }

    @Test
    fun `run with cancellation stops process`() {
        val toolPath = tempDir.resolve("slow-tool")
        Files.writeString(toolPath, "#!/bin/bash\nsleep 30\necho done")
        toolPath.toFile().setExecutable(true)

        val token = mockk<CancellationToken>(relaxed = true)
        every { token.isCancelled } returns false andThen true

        every { sdkManager.findTool(any()) } returns toolPath

        val result = runner().run("slow-tool", listOf(), ToolOptions(cancellationToken = token, timeoutMs = 5000))
        assertFalse(result.succeeded)
        assertEquals(com.hbe.api.ToolErrorType.CANCELLED, result.error!!.type)
    }

    @Test
    fun `run handles process exception gracefully`() {
        every { sdkManager.findTool(any()) } returns Path.of("/usr/bin/java")
        every { processRunner.runWithTimeout(any(), any(), any(), any()) } throws
            RuntimeException("Process crashed")

        val result = runner().run("java", listOf("-version"))
        assertFalse(result.succeeded)
        assertNotNull(result.error)
        assertEquals(com.hbe.api.ToolErrorType.EXECUTION_ERROR, result.error!!.type)
    }

    @Test
    fun `run returns stdout and stderr content`() {
        every { sdkManager.findTool(any()) } returns Path.of("/usr/bin/java")
        every { processRunner.runWithTimeout(any(), any(), any(), any()) } returns
            ProcessResult(0, "line1\nline2", "stderr line", 10)

        val result = runner().run("java", listOf("-version"))
        assertTrue(result.succeeded)
        assertEquals("line1\nline2\n", result.stdout)
        assertEquals("stderr line\n", result.stderr)
    }

    @Test
    fun `run with empty args succeeds`() {
        every { sdkManager.findTool("java") } returns Path.of("/usr/bin/java")
        every { processRunner.runWithTimeout(any(), any(), any(), any()) } returns
            ProcessResult(0, "", "", 5)

        val result = runner().run("java", emptyList())
        assertTrue(result.succeeded)
    }

    @Test
    fun `run reports correct duration`() {
        every { sdkManager.findTool(any()) } returns Path.of("/usr/bin/java")
        every { processRunner.runWithTimeout(any(), any(), any(), any()) } returns
            ProcessResult(0, "", "", 150)

        val result = runner().run("java", listOf("-version"))
        assertTrue(result.durationMs >= 0)
    }

    @Test
    fun `run resolves javac through sdkManager`() {
        every { sdkManager.findTool("javac") } returns Path.of("/usr/bin/javac")
        every { processRunner.runWithTimeout(any(), any(), any(), any()) } returns
            ProcessResult(0, "javac 17.0.8", "", 10)

        val result = runner().run("javac", listOf("-version"))
        assertTrue(result.succeeded)
        verify { sdkManager.findTool("javac") }
    }

    @Test
    fun `run resolves kotlinc through sdkManager`() {
        every { sdkManager.findTool("kotlinc") } returns Path.of("/usr/bin/kotlinc")
        every { processRunner.runWithTimeout(any(), any(), any(), any()) } returns
            ProcessResult(0, "Kotlin Compiler 1.9.24", "", 10)

        val result = runner().run("kotlinc", listOf("-version"))
        assertTrue(result.succeeded)
        verify { sdkManager.findTool("kotlinc") }
    }

    @Test
    fun `tool not found falls back to processRunner`() {
        every { sdkManager.findTool(any()) } returns null
        every { processRunner.findTool(any()) } returns Path.of("/fallback/zipalign")
        every { processRunner.runWithTimeout(any(), any(), any(), any()) } returns
            ProcessResult(0, "", "", 5)

        val result = runner().run("zipalign", listOf("-v"))
        assertTrue(result.succeeded)
        verify { processRunner.findTool("zipalign") }
    }

    @Test
    fun `cancellation during process execution kills process`() {
        val script = tempDir.resolve("slow.sh")
        Files.writeString(script, "#!/bin/bash\ntrap 'exit 1' INT\ni=0\nwhile true; do sleep 1; done")
        script.toFile().setExecutable(true)

        val token = mockk<CancellationToken>(relaxed = true)
        every { token.isCancelled } returnsMany listOf(false, false, true)

        every { sdkManager.findTool("slow") } returns script

        val result = runner().run("slow", listOf(), ToolOptions(cancellationToken = token, timeoutMs = 10000))
        assertFalse(result.succeeded)
    }

    @Test
    fun `tool options default values are sensible`() {
        val opts = ToolOptions()
        assertEquals(300_000, opts.timeoutMs)
        assertEquals(256, opts.maxHeapMb)
        assertNull(opts.workingDir)
        assertTrue(opts.environment.isEmpty())
        assertTrue(opts.captureOutput)
        assertNull(opts.cancellationToken)
    }
}
