package com.hbe.api

import java.nio.file.Path

interface SdkManager {
    fun resolveSdk(compileSdk: Int, buildToolsVersion: String? = null): SdkResolution
    fun doctor(): SdkDiagnosis
    fun downloadPlatform(apiLevel: Int)
    fun downloadBuildTools(version: String)
    fun getSdkPath(): Path?
    fun getJdkPath(): Path?
    fun getToolPath(toolName: String): Path?
    fun findTool(toolName: String): Path?
    fun listInstalledSdk(): SdkEnvironment
    fun listInstalledTools(): List<ToolInfo>
    fun validateEnvironment(): EnvironmentReport
}

data class SdkResolution(
    val sdkRoot: Path,
    val platformDir: Path,
    val buildToolsDir: Path,
    val platformToolsDir: Path? = null,
    val jdkHome: Path,
    val ndkDir: Path? = null,
    val cmdlineToolsDir: Path? = null
) {
    fun getToolPath(toolName: String): Path = buildToolsDir.resolve(toolName)
    val androidJar: Path get() = platformDir.resolve("android.jar")
}

data class SdkDiagnosis(
    val sdkFound: Boolean,
    val jdkFound: Boolean,
    val platforms: List<Int> = emptyList(),
    val buildToolsVersions: List<String> = emptyList(),
    val ndkFound: Boolean = false,
    val issues: List<String> = emptyList(),
    val recommendations: List<String> = emptyList()
)

data class ToolInfo(
    val name: String,
    val displayName: String,
    val version: String?,
    val path: Path?,
    val available: Boolean
)

data class SdkEnvironment(
    val sdkRoot: Path?,
    val platforms: List<Int>,
    val buildToolsVersions: List<String>,
    val platformToolsAvailable: Boolean,
    val ndkAvailable: Boolean
)

data class EnvironmentReport(
    val osName: String,
    val osArch: String,
    val osVersion: String,
    val isTermux: Boolean,
    val isGitHubActions: Boolean,
    val java: ToolInfo,
    val androidSdk: SdkEnvironment?,
    val tools: Map<String, ToolInfo>,
    val errors: List<String>,
    val warnings: List<String>
)
