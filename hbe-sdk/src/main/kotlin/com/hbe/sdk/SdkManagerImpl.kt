package com.hbe.sdk

import com.hbe.api.*
import com.hbe.api.exception.SdkException
import java.nio.file.Files
import java.nio.file.Path

class SdkManagerImpl(
    private val fileSystem: FileSystem,
    private val processRunner: ProcessRunner,
    private val logger: Logger
) : SdkManager {

    private var cachedResolution: SdkResolution? = null

    override fun resolveSdk(compileSdk: Int, buildToolsVersion: String?): SdkResolution {
        if (cachedResolution != null) return cachedResolution!!

        val sdkRoot = findSdkRoot()
            ?: throw SdkException("Android SDK not found. Set ANDROID_HOME or run 'hbe doctor'.")

        val buildToolsDir = findBuildTools(sdkRoot, buildToolsVersion)
        val platformDir = findPlatform(sdkRoot, compileSdk)
        val jdkHome = findJdk()
            ?: throw SdkException("JDK 17+ not found. Set JAVA_HOME or install JDK 17.")

        val resolution = SdkResolution(
            sdkRoot = sdkRoot,
            platformDir = platformDir,
            buildToolsDir = buildToolsDir,
            jdkHome = jdkHome
        )

        cachedResolution = resolution
        return resolution
    }

    override fun doctor(): SdkDiagnosis {
        val issues = mutableListOf<String>()
        val recommendations = mutableListOf<String>()

        val sdkRoot = findSdkRoot()
        if (sdkRoot == null) {
            issues.add("Android SDK not found")
            recommendations.add("Set ANDROID_HOME or run with auto-download enabled")
        }

        val jdkHome = findJdk()
        if (jdkHome == null) {
            issues.add("JDK 17+ not found")
            recommendations.add("Install JDK 17 or set JAVA_HOME")
        }

        return SdkDiagnosis(
            sdkFound = sdkRoot != null,
            jdkFound = jdkHome != null,
            issues = issues,
            recommendations = recommendations
        )
    }

    override fun downloadPlatform(apiLevel: Int) {
        logger.info("SDK platform download not yet implemented", mapOf("apiLevel" to apiLevel.toString()))
    }

    override fun downloadBuildTools(version: String) {
        logger.info("Build tools download not yet implemented", mapOf("version" to version))
    }

    override fun getSdkPath(): Path? = findSdkRoot()

    override fun getJdkPath(): Path? = findJdk()

    override fun getToolPath(toolName: String): Path? {
        val resolution = cachedResolution ?: return processRunner.findTool(toolName)
        return resolution.buildToolsDir.resolve(toolName).takeIf { Files.isExecutable(it) }
            ?: processRunner.findTool(toolName)
    }

    private fun findSdkRoot(): Path? {
        val envVars = listOf("ANDROID_HOME", "ANDROID_SDK_ROOT")
        for (envVar in envVars) {
            val value = System.getenv(envVar)
            if (value != null) {
                val path = Path.of(value)
                if (Files.isDirectory(path)) return path.toAbsolutePath()
            }
        }

        val userHome = System.getProperty("user.home")
        if (userHome != null) {
            val candidates = listOf(
                Path.of(userHome, ".hbe", "sdk"),
                Path.of(userHome, "Android", "Sdk"),
                Path.of(userHome, "Library", "Android", "sdk")
            )
            for (candidate in candidates) {
                if (Files.isDirectory(candidate)) return candidate.toAbsolutePath()
            }
        }

        return null
    }

    private fun findBuildTools(sdkRoot: Path, version: String?): Path {
        val btDir = sdkRoot.resolve("build-tools")
        if (!Files.isDirectory(btDir)) {
            throw SdkException("build-tools directory not found in SDK: $btDir")
        }

        val versions = Files.list(btDir)
            .filter { Files.isDirectory(it) }
            .map { it.fileName.toString() }
            .toList()
            .sortedDescending()

        if (versions.isEmpty()) {
            throw SdkException("No build-tools found. Install build-tools for your SDK version.")
        }

        val selected = version ?: versions.first()
        return btDir.resolve(selected)
    }

    private fun findPlatform(sdkRoot: Path, apiLevel: Int): Path {
        val platformDir = sdkRoot.resolve("platforms").resolve("android-$apiLevel")
        if (!Files.isDirectory(platformDir)) {
            throw SdkException(
                message = "SDK platform android-$apiLevel not found",
                suggestion = "Run 'hbe downloadSdk' with apiLevel=$apiLevel or compileSdk to an installed version"
            )
        }
        return platformDir
    }

    private fun findJdk(): Path? {
        val javaHome = System.getenv("JAVA_HOME")
        if (javaHome != null) {
            val path = Path.of(javaHome)
            if (Files.isDirectory(path)) return path.toAbsolutePath()
        }

        val userHome = System.getProperty("user.home")
        if (userHome != null) {
            val hbeJdk = Path.of(userHome, ".hbe", "jdk")
            if (Files.isDirectory(hbeJdk)) return hbeJdk.toAbsolutePath()
        }

        // Search common locations
        val candidates = listOf(
            Path.of("/usr/lib/jvm/java-17-openjdk-arm64"),
            Path.of("/usr/lib/jvm/java-17-openjdk-amd64"),
            Path.of("/usr/lib/jvm/java-11-openjdk-arm64"),
            Path.of("/usr/lib/jvm/java-11-openjdk-amd64")
        )
        for (candidate in candidates) {
            if (Files.isDirectory(candidate)) return candidate.toAbsolutePath()
        }

        return processRunner.findTool("java")?.parent?.parent
    }
}
