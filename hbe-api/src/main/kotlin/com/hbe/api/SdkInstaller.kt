package com.hbe.api

import java.nio.file.Path

interface SdkInstaller {
    fun installPlatform(
        apiLevel: Int,
        callback: ProgressCallback? = null,
        token: CancellationToken? = null
    ): SdkInstallResult

    fun installBuildTools(
        version: String,
        callback: ProgressCallback? = null,
        token: CancellationToken? = null
    ): SdkInstallResult

    fun getInstalledPlatforms(): List<Int>
    fun getInstalledBuildTools(): List<String>
    fun getCacheSize(): Long
    fun clearCache()
}

data class SdkInstallResult(
    val success: Boolean,
    val component: String,
    val version: String,
    val installPath: Path,
    val fromCache: Boolean = false,
    val bytesDownloaded: Long = 0,
    val wasResumed: Boolean = false,
    val error: String? = null
)

data class SdkInstallOptions(
    val sdkRoot: Path,
    val cacheDir: Path,
    val forceReinstall: Boolean = false,
    val verifySha256: Boolean = true,
    val osName: String = detectOs()
) {
    companion object {
        fun detectOs(): String {
            val name = System.getProperty("os.name", "").lowercase()
            return when {
                name.contains("linux") -> "linux"
                name.contains("mac") || name.contains("darwin") -> "mac"
                name.contains("win") -> "win"
                else -> "linux"
            }
        }
    }
}
