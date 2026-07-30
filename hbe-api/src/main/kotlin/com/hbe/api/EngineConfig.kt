package com.hbe.api

import com.hbe.api.Logger.LogLevel
import java.nio.file.Path

data class EngineConfig(
    val hbeHome: Path? = null,
    val sdkHome: Path? = null,
    val cacheHome: Path? = null,
    val buildOutputDir: Path? = null,
    val cacheMaxBytes: Long = 2L * 1024 * 1024 * 1024,
    val cacheMinFreeStorageBytes: Long = 500L * 1024 * 1024,
    val defaultRamBudgetMb: Int = 1024,
    val maxRamBudgetMb: Int = 4096,
    val autoTune: Boolean = true,
    val batchSizeJava: Int = 50,
    val batchSizeKotlin: Int = 30,
    val dexChunkSize: Int = 200,
    val connectTimeoutMs: Int = 10000,
    val readTimeoutMs: Int = 30000,
    val maxRetries: Int = 3,
    val autoDownloadSdk: Boolean = true,
    val autoCreateDebugKeystore: Boolean = true,
    val parallelPhases: Boolean = false,
    val daemonEnabled: Boolean = false,
    val logLevel: String = "INFO",
    val logFile: String? = null,
    val logOutput: Logger.LogOutput? = null,
    val proxy: ProxyConfig? = null
)

data class ProxyConfig(
    val host: String,
    val port: Int,
    val username: String? = null,
    val password: String? = null
)
