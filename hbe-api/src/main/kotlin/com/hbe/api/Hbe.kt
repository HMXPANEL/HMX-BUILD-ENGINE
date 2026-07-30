package com.hbe.api

import com.hbe.api.dto.*

object Hbe {

    fun build(request: BuildRequest): BuildResult {
        return HbeEngineFactory.createEngine().build(request)
    }

    fun clean(request: CleanRequest): CleanResult {
        return HbeEngineFactory.createEngine().clean(request)
    }

    fun doctor(request: DoctorRequest): HealthReport {
        return HbeEngineFactory.createEngine().doctor(request)
    }

    fun install(request: InstallRequest): InstallResult {
        return HbeEngineFactory.createEngine().install(request)
    }

    fun downloadSdk(request: DownloadSdkRequest): DownloadResult {
        return HbeEngineFactory.createEngine().downloadSdk(request)
    }

    fun resolveDependencies(request: ResolveRequest): ResolveResult {
        return HbeEngineFactory.createEngine().resolveDependencies(request)
    }

    fun analyze(request: AnalyzeRequest): AnalyzeResult {
        return HbeEngineFactory.createEngine().analyze(request)
    }

    fun cache(request: CacheRequest): CacheOperationResult {
        return HbeEngineFactory.createEngine().cache(request)
    }

    fun shutdown() {
        HbeEngineFactory.createEngine().shutdown()
    }
}

interface HbeEngine {
    fun build(request: BuildRequest): BuildResult
    fun clean(request: CleanRequest): CleanResult
    fun doctor(request: DoctorRequest): HealthReport
    fun install(request: InstallRequest): InstallResult
    fun downloadSdk(request: DownloadSdkRequest): DownloadResult
    fun resolveDependencies(request: ResolveRequest): ResolveResult
    fun analyze(request: AnalyzeRequest): AnalyzeResult
    fun cache(request: CacheRequest): CacheOperationResult
    fun shutdown()
}

object HbeEngineFactory {
    private var engine: HbeEngine? = null

    fun createEngine(): HbeEngine {
        if (engine == null) {
            engine = createDefaultEngine()
        }
        return engine!!
    }

    fun setEngine(custom: HbeEngine) {
        engine = custom
    }

    fun reset() {
        engine = null
    }

    private fun createDefaultEngine(): HbeEngine {
        throw UnsupportedOperationException("HBE engine not yet implemented. Use HbeEngineFactory.setEngine() for testing.")
    }
}

data class CleanRequest(
    val projectDir: String,
    val cleanAll: Boolean = false
)

data class CleanResult(
    val status: BuildResult.Status,
    val cleanedBytes: Long = 0,
    val message: String = ""
)

data class DoctorRequest(
    val json: Boolean = false
)

data class InstallRequest(
    val apkPath: String,
    val deviceId: String? = null
)

data class InstallResult(
    val status: BuildResult.Status,
    val message: String = "",
    val deviceSerial: String? = null
)

data class DownloadSdkRequest(
    val apiLevel: Int,
    val buildToolsVersion: String? = null
)

data class DownloadResult(
    val status: BuildResult.Status,
    val message: String = ""
)

data class ResolveRequest(
    val projectDir: String,
    val repositories: List<String> = DependencyManager.defaultRepos
)

data class ResolveResult(
    val status: BuildResult.Status,
    val resolved: Int = 0,
    val failed: Int = 0,
    val details: List<String> = emptyList()
)

data class AnalyzeRequest(
    val projectDir: String,
    val json: Boolean = false
)

data class AnalyzeResult(
    val status: BuildResult.Status,
    val projectType: String = "",
    val modules: List<String> = emptyList(),
    val compileSdk: Int? = null,
    val minSdk: Int? = null,
    val targetSdk: Int? = null,
    val dependencies: List<String> = emptyList(),
    val hasCompose: Boolean = false,
    val sourceFileCount: Int = 0,
    val details: Map<String, Any> = emptyMap()
)

data class CacheRequest(
    val command: CacheCommand,
    val projectDir: String? = null
)

enum class CacheCommand {
    STATS,
    PRUNE,
    CLEAR
}

data class CacheOperationResult(
    val status: BuildResult.Status,
    val message: String = "",
    val stats: CacheStats? = null
)
