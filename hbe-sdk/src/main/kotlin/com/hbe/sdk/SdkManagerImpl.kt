package com.hbe.sdk

import com.hbe.api.*
import com.hbe.api.exception.SdkException
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.regex.Pattern

class SdkManagerImpl(
    private val fileSystem: FileSystem,
    private val processRunner: ProcessRunner,
    private val logger: Logger,
    private val env: Map<String, String> = System.getenv()
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
        val javaVersion = if (jdkHome != null) detectJavaVersion() else null
        if (jdkHome == null) {
            issues.add("JDK 17+ not found")
            recommendations.add("Install JDK 17 or set JAVA_HOME")
        } else if (javaVersion != null && javaVersion.first < 17) {
            issues.add("JDK version ${javaVersion.first} detected, 17+ required")
            recommendations.add("Install JDK 17+")
        }

        return SdkDiagnosis(
            sdkFound = sdkRoot != null,
            jdkFound = jdkHome != null,
            platforms = listPlatforms(sdkRoot),
            buildToolsVersions = listBuildToolVersions(sdkRoot),
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
        val resolution = cachedResolution ?: return findToolOnPath(toolName)
        return resolution.buildToolsDir.resolve(toolName).takeIf { Files.isExecutable(it) }
            ?: findToolOnPath(toolName)
    }

    override fun findTool(toolName: String): Path? {
        val name = toolName.trim()
        when {
            name == "java" || name == "javac" || name == "jar" -> {
                val jdkHome = findJdk()
                if (jdkHome != null) {
                    val binPath = jdkHome.resolve("bin").resolve(name)
                    if (Files.isExecutable(binPath)) return binPath.toAbsolutePath()
                }
            }
            name == "adb" -> {
                val sdkRoot = findSdkRoot()
                if (sdkRoot != null) {
                    val adbPath = sdkRoot.resolve("platform-tools").resolve("adb")
                    if (Files.isExecutable(adbPath)) return adbPath.toAbsolutePath()
                }
            }
            name in listOf("aapt2", "d8", "zipalign", "apksigner") -> {
                val sdkRoot = findSdkRoot()
                if (sdkRoot != null) {
                    val toolsDir = sdkRoot.resolve("build-tools")
                    if (Files.isDirectory(toolsDir)) {
                        val versions = Files.list(toolsDir)
                            .filter { Files.isDirectory(it) }
                            .sorted(java.util.Comparator.reverseOrder())
                            .toList()
                        for (versionDir in versions) {
                            val toolPath = versionDir.resolve(name)
                            if (Files.isExecutable(toolPath)) return toolPath.toAbsolutePath()
                        }
                    }
                }
            }
        }
        return findToolOnPath(name)
    }

    override fun listInstalledSdk(): SdkEnvironment {
        val sdkRoot = findSdkRoot()
        if (sdkRoot == null) {
            return SdkEnvironment(
                sdkRoot = null,
                platforms = emptyList(),
                buildToolsVersions = emptyList(),
                platformToolsAvailable = false,
                ndkAvailable = false
            )
        }
        return SdkEnvironment(
            sdkRoot = sdkRoot,
            platforms = listPlatforms(sdkRoot),
            buildToolsVersions = listBuildToolVersions(sdkRoot),
            platformToolsAvailable = Files.isDirectory(sdkRoot.resolve("platform-tools")),
            ndkAvailable = Files.isDirectory(sdkRoot.resolve("ndk")) || Files.isDirectory(sdkRoot.resolve("ndk-bundle"))
        )
    }

    override fun listInstalledTools(): List<ToolInfo> {
        val tools = mutableListOf<ToolInfo>()

        val javaPath = findTool("java")
        val javaVersion = if (javaPath != null) detectJavaVersion() else null
        tools.add(ToolInfo("java", "Java Runtime", javaVersion?.second, javaPath, javaPath != null))

        val javacPath = findTool("javac")
        val javacVersion = if (javacPath != null) detectJavacVersion() else null
        tools.add(ToolInfo("javac", "Java Compiler", javacVersion, javacPath, javacPath != null))

        val sdkRoot = findSdkRoot()

        val aapt2Path = findAndroidTool("aapt2")
        val aapt2Version = if (aapt2Path != null) detectAapt2Version(aapt2Path) else null
        tools.add(ToolInfo("aapt2", "Android Asset Packaging Tool 2", aapt2Version, aapt2Path, aapt2Path != null))

        val d8Path = findAndroidTool("d8")
        val d8Version = if (d8Path != null) detectD8Version(d8Path) else null
        tools.add(ToolInfo("d8", "D8 Dex Compiler", d8Version, d8Path, d8Path != null))

        val zipalignPath = findAndroidTool("zipalign")
        tools.add(ToolInfo("zipalign", "Zip Aligner", null, zipalignPath, zipalignPath != null))

        val apksignerPath = findAndroidTool("apksigner")
        val apksignerVersion = if (apksignerPath != null) detectApksignerVersion(apksignerPath) else null
        tools.add(ToolInfo("apksigner", "APK Signer", apksignerVersion, apksignerPath, apksignerPath != null))

        val adbPath = findTool("adb")
        val adbVersion = if (adbPath != null) detectAdbVersion(adbPath) else null
        tools.add(ToolInfo("adb", "Android Debug Bridge", adbVersion, adbPath, adbPath != null))

        return tools
    }

    override fun validateEnvironment(): EnvironmentReport {
        val osName = System.getProperty("os.name") ?: "unknown"
        val osArch = System.getProperty("os.arch") ?: "unknown"
        val osVersion = System.getProperty("os.version") ?: "unknown"
        val isTermux = detectTermux()
        val isGitHubActions = env["GITHUB_ACTIONS"] == "true"

        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        val javaPath = findTool("java")
        val javaVersion = if (javaPath != null) detectJavaVersion() else null
        val javaInfo = ToolInfo("java", "Java Runtime", javaVersion?.second, javaPath, javaPath != null)

        if (javaPath == null) {
            errors.add("Java not found. Install JDK 17+ and set JAVA_HOME.")
        } else if (javaVersion != null && javaVersion.first < 17) {
            errors.add("Java version ${javaVersion.first} detected. JDK 17+ required.")
        }

        val sdkInfo = listInstalledSdk()

        val allTools = listInstalledTools().associateBy { it.name }
        for ((name, tool) in allTools) {
            if (!tool.available && name != "java" && name != "javac") {
                warnings.add("$name not found. Some features may be unavailable.")
            }
        }

        return EnvironmentReport(
            osName = osName,
            osArch = osArch,
            osVersion = osVersion,
            isTermux = isTermux,
            isGitHubActions = isGitHubActions,
            java = javaInfo,
            androidSdk = sdkInfo,
            tools = allTools,
            errors = errors,
            warnings = warnings
        )
    }

    private fun findSdkRoot(): Path? {
        val envVars = listOf("ANDROID_HOME", "ANDROID_SDK_ROOT")
        for (envVar in envVars) {
            val value = env[envVar]
            if (value != null) {
                val path = Path.of(value)
                if (Files.isDirectory(path)) return path.toAbsolutePath()
            }
        }

        val userHome = System.getProperty("user.home")
        if (userHome != null) {
            for (candidate in listOf(
                Path.of(userHome, ".hbe", "sdk"),
                Path.of(userHome, "Android", "Sdk"),
                Path.of(userHome, "Library", "Android", "sdk")
            )) {
                if (Files.isDirectory(candidate)) return candidate.toAbsolutePath()
            }
        }

        if (detectTermux()) {
            val prefix = env["PREFIX"] ?: "/data/data/com.termux/files/usr"
            val termuxSdk = Path.of(prefix).resolve("android-sdk")
            if (Files.isDirectory(termuxSdk)) return termuxSdk.toAbsolutePath()
        }

        return null
    }

    private fun findJdk(): Path? {
        val javaHome = env["JAVA_HOME"]
        if (javaHome != null) {
            val path = Path.of(javaHome)
            if (Files.isDirectory(path)) return path.toAbsolutePath()
        }

        val userHome = System.getProperty("user.home")
        if (userHome != null) {
            val hbeJdk = Path.of(userHome, ".hbe", "jdk")
            if (Files.isDirectory(hbeJdk)) return hbeJdk.toAbsolutePath()
        }

        val javaPath = findToolOnPath("java")
        if (javaPath != null) {
            val resolved = javaPath.toAbsolutePath().normalize()
            val jdkDir = resolved.parent?.parent
            if (jdkDir != null && Files.isDirectory(jdkDir.resolve("bin").resolve("javac"))) {
                return jdkDir
            }
            val jdkDirLib = resolved.parent?.parent?.parent
            if (jdkDirLib != null && Files.isDirectory(jdkDirLib.resolve("bin").resolve("javac"))) {
                return jdkDirLib
            }
        }

        return null
    }

    private fun findBuildTools(sdkRoot: Path, version: String?): Path {
        val btDir = sdkRoot.resolve("build-tools")
        if (!Files.isDirectory(btDir)) {
            throw SdkException("build-tools directory not found in SDK: $btDir")
        }

        val versions = listBuildToolVersions(sdkRoot)

        if (versions.isEmpty()) {
            throw SdkException("No build-tools found. Install build-tools for your SDK version.")
        }

        val selected = if (version != null && version in versions) version else versions.first()
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

    private fun findAndroidTool(name: String): Path? {
        val sdkRoot = findSdkRoot() ?: return null
        val toolsDir = sdkRoot.resolve("build-tools")
        if (!Files.isDirectory(toolsDir)) return findToolOnPath(name)

        val versions = Files.list(toolsDir)
            .filter { Files.isDirectory(it) }
            .sorted(java.util.Comparator.reverseOrder())
            .toList()

        for (versionDir in versions) {
            val toolPath = versionDir.resolve(name)
            if (Files.exists(toolPath)) {
                if (Files.isExecutable(toolPath) || Files.isRegularFile(toolPath)) {
                    return toolPath.toAbsolutePath()
                }
            }
        }

        return findToolOnPath(name)
    }

    private fun findToolOnPath(name: String): Path? {
        val pathEnv = env["PATH"] ?: return null
        val pathDirs = pathEnv.split(File.pathSeparator)

        for (dir in pathDirs) {
            val toolPath = Path.of(dir, name)
            if (Files.isExecutable(toolPath)) return toolPath.toAbsolutePath()
            val toolPathExe = Path.of(dir, "$name.exe")
            if (Files.isExecutable(toolPathExe)) return toolPathExe.toAbsolutePath()
        }

        return null
    }

    private fun detectJavaVersion(): Pair<Int, String>? {
        val javaPath = findToolOnPath("java") ?: return null
        val result = processRunner.run(javaPath.toString(), listOf("-version"))
        val output = if (result.stderr.isNotBlank()) result.stderr else result.stdout
        return parseJavaVersion(output)
    }

    private fun detectJavacVersion(): String? {
        val javacPath = findToolOnPath("javac") ?: return null
        val result = processRunner.run(javacPath.toString(), listOf("-version"))
        val output = if (result.stdout.isNotBlank()) result.stdout else result.stderr
        return parseJavacVersion(output)
    }

    private fun detectAapt2Version(toolPath: Path): String? {
        val result = processRunner.run(toolPath.toString(), listOf("version"))
        val output = if (result.stdout.isNotBlank()) result.stdout else result.stderr
        return parseAapt2Version(output)
    }

    private fun detectD8Version(toolPath: Path): String? {
        val result = processRunner.run(toolPath.toString(), listOf("--version"))
        val output = if (result.stdout.isNotBlank()) result.stdout else result.stderr
        return parseD8Version(output)
    }

    private fun detectApksignerVersion(toolPath: Path): String? {
        val result = processRunner.run(toolPath.toString(), listOf("version"))
        val output = if (result.stdout.isNotBlank()) result.stdout else result.stderr
        return parseApksignerVersion(output)
    }

    private fun detectAdbVersion(toolPath: Path): String? {
        val result = processRunner.run(toolPath.toString(), listOf("--version"))
        val output = if (result.stdout.isNotBlank()) result.stdout else result.stderr
        return parseAdbVersion(output)
    }

    private fun listPlatforms(sdkRoot: Path?): List<Int> {
        if (sdkRoot == null) return emptyList()
        val platformsDir = sdkRoot.resolve("platforms")
        if (!Files.isDirectory(platformsDir)) return emptyList()
        return Files.list(platformsDir)
            .filter { Files.isDirectory(it) }
            .map { it.fileName.toString() }
            .filter { it.startsWith("android-") }
            .mapNotNull { it.removePrefix("android-").toIntOrNull() }
            .sorted()
    }

    private fun listBuildToolVersions(sdkRoot: Path?): List<String> {
        if (sdkRoot == null) return emptyList()
        val btDir = sdkRoot.resolve("build-tools")
        if (!Files.isDirectory(btDir)) return emptyList()
        return Files.list(btDir)
            .filter { Files.isDirectory(it) }
            .map { it.fileName.toString() }
            .sortedDescending()
    }

    private fun detectTermux(): Boolean {
        if (env["PREFIX"] != null) return true
        val classPath = System.getProperty("java.class.path") ?: ""
        if ("com.termux" in classPath) return true
        val osName = System.getProperty("os.name", "")
        return "android" in osName.lowercase()
    }

    companion object {
        private val JAVA_VERSION_PATTERN = Pattern.compile("""(?:openjdk|java)\s+version\s+\"(\d+)""", Pattern.CASE_INSENSITIVE)
        private val JAVAC_VERSION_PATTERN = Pattern.compile("""javac\s+(\d+\.\d+\.\d+)""", Pattern.CASE_INSENSITIVE)
        private val AAPT2_VERSION_PATTERN = Pattern.compile("""aapt\s*2\s+version\s+([\d.]+)""", Pattern.CASE_INSENSITIVE)
        private val D8_VERSION_PATTERN = Pattern.compile("""version\s+([\d.]+)""", Pattern.CASE_INSENSITIVE)
        private val APKSIGNER_VERSION_PATTERN = Pattern.compile("""apksigner\s+version\s+([\d.]+)""", Pattern.CASE_INSENSITIVE)
        private val ADB_VERSION_PATTERN = Pattern.compile("""Android Debug Bridge version\s+([\d.]+)""", Pattern.CASE_INSENSITIVE)

        fun parseJavaVersion(output: String): Pair<Int, String>? {
            val matcher = JAVA_VERSION_PATTERN.matcher(output)
            if (matcher.find()) {
                val major = matcher.group(1).toIntOrNull() ?: return null
                val fullLine = output.lines().firstOrNull { it.contains("version") }?.trim() ?: "unknown"
                return major to fullLine
            }
            return null
        }

        fun parseJavacVersion(output: String): String? {
            val matcher = JAVAC_VERSION_PATTERN.matcher(output)
            return if (matcher.find()) matcher.group(1) else null
        }

        fun parseAapt2Version(output: String): String? {
            val matcher = AAPT2_VERSION_PATTERN.matcher(output)
            return if (matcher.find()) matcher.group(1) else null
        }

        fun parseD8Version(output: String): String? {
            val matcher = D8_VERSION_PATTERN.matcher(output)
            return if (matcher.find()) matcher.group(1) else null
        }

        fun parseApksignerVersion(output: String): String? {
            val matcher = APKSIGNER_VERSION_PATTERN.matcher(output)
            return if (matcher.find()) matcher.group(1) else null
        }

        fun parseAdbVersion(output: String): String? {
            val matcher = ADB_VERSION_PATTERN.matcher(output)
            return if (matcher.find()) matcher.group(1) else null
        }
    }
}
