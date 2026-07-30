package com.hbe.core

import com.hbe.api.*
import com.hbe.api.dto.BuildResult
import com.hbe.api.dto.BuildError
import com.hbe.api.dto.PhaseTiming

class PhaseExecutor(
    private val logger: Logger
) {
    fun executeBuild(context: BuildContext): BuildResult {
        logger.info("Build started", mapOf("buildId" to context.buildId, "project" to context.request.projectDir))

        val phaseTimings = mutableListOf<PhaseTiming>()
        var ramPeak = 0L
        var cacheHits = 0
        var cacheMisses = 0

        try {
            // Placeholder: phases will be scheduled by the TaskScheduler
            logger.info("Build completed", mapOf("buildId" to context.buildId))

            return BuildResult(
                status = BuildResult.Status.SUCCESS,
                buildId = context.buildId,
                phases = phaseTimings,
                totalDurationMs = 0,
                ramPeakBytes = ramPeak,
                cacheHits = cacheHits,
                cacheMisses = cacheMisses,
                metadata = mapOf(
                    "note" to "Foundation build — phase execution not yet implemented"
                )
            )
        } catch (e: BuildCancelledException) {
            return BuildResult(
                status = BuildResult.Status.FAILURE,
                error = BuildError(
                    phase = "BUILD",
                    code = "CANCELLED",
                    message = "Build was cancelled"
                ),
                buildId = context.buildId,
                phases = phaseTimings
            )
        }
    }
}
