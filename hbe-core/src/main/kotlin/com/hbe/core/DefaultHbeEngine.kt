package com.hbe.core

import com.hbe.api.*
import com.hbe.api.dto.*
import java.util.UUID

class DefaultHbeEngine(
    private val configLoader: ConfigLoader,
    private val phaseExecutor: PhaseExecutor,
    private val diagnostics: Diagnostics
) : HbeEngine {

    override fun build(request: BuildRequest): BuildResult {
        val buildId = UUID.randomUUID().toString().substring(0, 8)
        val config = configLoader.loadConfig()
        val appliedConfig = applyRequestToConfig(request, config)

        val buildContext = BuildContextImpl(
            buildId = "bld-$buildId",
            request = request,
            config = appliedConfig
        )

        try {
            return phaseExecutor.executeBuild(buildContext)
        } catch (e: Exception) {
            return BuildResult(
                status = BuildResult.Status.FAILURE,
                error = BuildError(
                    phase = "BUILD",
                    code = "INTERNAL_ERROR",
                    message = e.message ?: "Unknown build error",
                    suggestion = "Check the logs for details"
                ),
                buildId = buildContext.buildId
            )
        }
    }

    override fun clean(request: CleanRequest): CleanResult {
        return CleanResult(status = BuildResult.Status.SUCCESS, message = "Clean not yet implemented")
    }

    override fun doctor(request: DoctorRequest): HealthReport {
        return diagnostics.doctor()
    }

    override fun install(request: InstallRequest): InstallResult {
        return InstallResult(
            status = BuildResult.Status.FAILURE,
            message = "ADB install not yet implemented"
        )
    }

    override fun downloadSdk(request: DownloadSdkRequest): DownloadResult {
        return DownloadResult(
            status = BuildResult.Status.FAILURE,
            message = "SDK download not yet implemented"
        )
    }

    override fun resolveDependencies(request: ResolveRequest): ResolveResult {
        return ResolveResult(
            status = BuildResult.Status.FAILURE
        )
    }

    override fun analyze(request: AnalyzeRequest): AnalyzeResult {
        return AnalyzeResult(
            status = BuildResult.Status.FAILURE
        )
    }

    override fun cache(request: CacheRequest): CacheOperationResult {
        return CacheOperationResult(
            status = BuildResult.Status.FAILURE,
            message = "Cache operations not yet implemented"
        )
    }

    override fun shutdown() {
        // Cleanup resources
    }

    private fun applyRequestToConfig(request: BuildRequest, config: EngineConfig): EngineConfig {
        return config.copy(
            defaultRamBudgetMb = request.ramBudgetMb,
            autoTune = request.ramBudgetMb < 2048
        )
    }
}
