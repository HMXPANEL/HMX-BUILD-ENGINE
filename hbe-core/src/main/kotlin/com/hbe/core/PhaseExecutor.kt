package com.hbe.core

import com.hbe.api.*
import com.hbe.api.dto.BuildResult
import com.hbe.api.dto.BuildError
import com.hbe.api.dto.PhaseTiming

class PhaseExecutor(
    private val logger: Logger,
    private val pipeline: BuildPipeline? = null,
    private val incrementalPipeline: BuildPipeline? = null
) {
    fun executeBuild(context: BuildContext): BuildResult {
        logger.info("Build started", mapOf("buildId" to context.buildId, "project" to context.request.projectDir))

        val target = if (context.request.incremental) incrementalPipeline ?: pipeline else pipeline
        if (target != null) {
            return target.execute(context)
        }

        // Placeholder fallback when no pipeline is wired (e.g. older integration tests)
        logger.info("Build completed", mapOf("buildId" to context.buildId))

        return BuildResult(
            status = BuildResult.Status.SUCCESS,
            buildId = context.buildId,
            totalDurationMs = 0,
            metadata = mapOf(
                "note" to "Foundation build — phase execution not yet implemented"
            )
        )
    }
}
