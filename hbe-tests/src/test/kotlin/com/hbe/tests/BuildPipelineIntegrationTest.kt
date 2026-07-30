package com.hbe.tests

import com.hbe.api.*
import com.hbe.api.dto.*
import com.hbe.api.exception.BuildException
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
}
