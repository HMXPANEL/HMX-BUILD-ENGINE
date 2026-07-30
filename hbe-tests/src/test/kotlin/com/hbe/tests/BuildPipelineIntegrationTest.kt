package com.hbe.tests

import com.hbe.api.*
import com.hbe.api.dto.*
import com.hbe.api.exception.BuildException
import com.hbe.core.ConfigLoader
import com.hbe.core.DefaultHbeEngine
import com.hbe.core.DefaultLogger
import com.hbe.core.PhaseExecutor
import com.hbe.diagnostics.DiagnosticsImpl
import com.hbe.infra.JavaNetHttpClient
import com.hbe.infra.OsFileSystem
import com.hbe.infra.OsProcessRunner
import com.hbe.sdk.SdkManagerImpl
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class BuildPipelineIntegrationTest {

    @Test
    fun `MavenCoordinate round-trip serialization`() {
        val original = MavenCoordinate.parse("androidx.core:core-ktx:1.12.0")
        val parsed = MavenCoordinate.parse(original.toNotation())
        assertEquals(original, parsed)
    }

    @Test
    fun `BuildRequest can be created with defaults`() {
        val request = BuildRequest(
            projectDir = "/test/project"
        )
        assertEquals("debug", request.variant)
        assertTrue(request.incremental)
        assertEquals(1024, request.ramBudgetMb)
    }

    @Test
    fun `SigningConfig debug creates correctly`() {
        val config = SigningConfig.debug()
        assertEquals(SigningConfig.SigningType.DEBUG, config.type)
        assertTrue(config.autoGenerate)
    }

    @Test
    fun `BuildResult success state`() {
        val result = BuildResult(
            status = BuildResult.Status.SUCCESS,
            buildId = "test-001",
            apkPath = "/tmp/test.apk",
            totalDurationMs = 45000,
            cacheHits = 5
        )
        assertEquals(BuildResult.Status.SUCCESS, result.status)
        assertNotNull(result.buildId)
    }

    @Test
    fun `BuildResult failure state includes error`() {
        val error = BuildError(
            phase = "SOURCE_COMPILE",
            code = "KOTLINC_COMPILE_ERROR",
            message = "Compilation failed",
            suggestion = "Fix the error and rebuild",
            details = listOf("Main.kt:42: Unresolved reference")
        )
        val result = BuildResult(
            status = BuildResult.Status.FAILURE,
            error = error
        )
        assertEquals("SOURCE_COMPILE", result.error?.phase)
        assertNotNull(result.error?.suggestion)
    }

    @Test
    fun `PhaseTiming with skipped status`() {
        val timing = PhaseTiming(
            name = "RESOURCE_LINK",
            status = PhaseTiming.PhaseStatus.SKIPPED,
            cacheHit = true
        )
        assertEquals(PhaseTiming.PhaseStatus.SKIPPED, timing.status)
        assertTrue(timing.cacheHit)
    }

    @Test
    fun `DefaultHbeEngine build returns success with placeholder phases`() {
        val logger = DefaultLogger()
        val fileSystem = OsFileSystem()
        val processRunner = OsProcessRunner()
        val configLoader = ConfigLoader(logger)
        val phaseExecutor = PhaseExecutor(logger)
        val sdkManager = SdkManagerImpl(fileSystem, processRunner, logger)
        val diagnostics = DiagnosticsImpl(sdkManager, logger)
        val engine = DefaultHbeEngine(configLoader, phaseExecutor, diagnostics)

        val result = engine.build(BuildRequest(projectDir = "."))

        assertEquals(BuildResult.Status.SUCCESS, result.status)
        assertTrue(result.buildId.startsWith("bld-"))
        assertNotNull(result.metadata["note"])
        logger.info("Pipeline integration test passed", mapOf("buildId" to result.buildId))
    }
}
