package com.hbe.core.project

import com.hbe.api.FileSystem
import com.hbe.api.Logger
import java.nio.file.Path
import java.util.regex.Pattern

/**
 * Imports an Android/Gradle project without running Gradle: reads
 * settings.gradle(.kts), per-module build.gradle(.kts), gradle.properties
 * and local.properties and extracts modules, SDK versions, build variants,
 * source sets and Maven dependencies.
 */
class ProjectImporter(
    private val fileSystem: FileSystem,
    private val logger: Logger
) {

    fun importProject(rootDir: Path): ProjectModel {
        val root = rootDir.toAbsolutePath().normalize()
        if (!fileSystem.exists(root)) {
            throw IllegalArgumentException("Project directory not found: $root")
        }

        val settings = readTextIfExists(listOf(
            root.resolve("settings.gradle.kts"),
            root.resolve("settings.gradle")
        ))

        // Parse version catalog if present
        val catalogFile = listOf(
            root.resolve("gradle/libs.versions.toml"),
            root.resolve("gradle/libs.versions.toml")
        ).firstOrNull { fileExists(it) }
        val catalog = if (catalogFile != null) {
            logger.info("Parsing version catalog", mapOf("file" to catalogFile.toString()))
            VersionCatalogParser(fileSystem).parse(catalogFile)
        } else {
            VersionCatalogParser.Catalog()
        }

        val modules = detectModules(settings, root)
        val repositories = detectRepositories(settings)

        val moduleModels = modules.map { modulePath ->
            importModule(root, modulePath, catalog)
        }

        return ProjectModel(
            rootDir = root,
            name = extractRootProjectName(settings) ?: root.fileName?.toString() ?: "project",
            modules = moduleModels,
            repositories = repositories,
            gradleProperties = readProperties(root.resolve("gradle.properties")),
            localProperties = readProperties(root.resolve("local.properties"))
        )
    }

    fun describe(model: ProjectModel): String {
        val sb = StringBuilder()
        sb.appendLine("Project: ${model.name}")
        sb.appendLine("Root: ${model.rootDir}")
        sb.appendLine()
        sb.appendLine("Build files detected:")
        sb.appendLine("  settings.gradle(.kts) : ${fileExists(model.rootDir.resolve("settings.gradle")) || fileExists(model.rootDir.resolve("settings.gradle.kts"))}")
        sb.appendLine("  gradle.properties      : ${fileExists(model.rootDir.resolve("gradle.properties"))}")
        sb.appendLine("  local.properties       : ${fileExists(model.rootDir.resolve("local.properties"))}")
        sb.appendLine()
        sb.appendLine("Repositories: ${if (model.repositories.isEmpty()) "(none detected)" else model.repositories.joinToString(", ")}")
        sb.appendLine()
        sb.appendLine("Modules (${model.modules.size}):")
        for (m in model.modules) {
            sb.appendLine("  ${m.path}  ${m.dir.fileName}")
            sb.appendLine("    plugin:        ${m.plugin ?: "?"}")
            sb.appendLine("    namespace:     ${m.namespace ?: "?"}")
            sb.appendLine("    applicationId: ${m.applicationId ?: "-"}")
            sb.appendLine("    compileSdk:    ${m.compileSdk ?: "?"}  minSdk: ${m.minSdk ?: "?"}  targetSdk: ${m.targetSdk ?: "?"}")
            sb.appendLine("    version:       ${m.versionName ?: "?"} (code ${m.versionCode ?: "?"})")
            sb.appendLine("    buildTypes:    ${if (m.buildTypes.isEmpty()) "debug" else m.buildTypes.joinToString(", ")}")
            sb.appendLine("    buildFeatures: ${if (m.buildFeatures.isEmpty()) "(default)" else m.buildFeatures.entries.joinToString(", ") { "${it.key}=${it.value}" }}")
            sb.appendLine("    manifest:      ${rel(m.manifest, model.rootDir)}")
            sb.appendLine("    res:           ${rel(m.resDir, model.rootDir)}")
            sb.appendLine("    java sources:  ${m.javaSourceDirs.joinToString(", ") { rel(it, model.rootDir) }}")
            sb.appendLine("    kotlin sources:${if (m.kotlinSourceDirs.isEmpty()) " (none)" else m.kotlinSourceDirs.joinToString(", ") { rel(it, model.rootDir) }}")
            if (m.sourceSets.isNotEmpty()) {
                sb.appendLine("    source sets:   ${m.sourceSets.entries.joinToString(", ") { "${it.key}=${it.value.joinToString("+")}" }}")
            }
            if (m.dependencies.isNotEmpty()) {
                sb.appendLine("    dependencies:")
                for (d in m.dependencies) sb.appendLine("      - $d")
            }
        }
        return sb.toString()
    }

    private fun importModule(root: Path, modulePath: String, catalog: VersionCatalogParser.Catalog = VersionCatalogParser.Catalog()): ModuleModel {
        val relative = modulePath.removePrefix(":").replace(":", "/")
        val dir = root.resolve(relative)
        val buildFile = listOf(dir.resolve("build.gradle.kts"), dir.resolve("build.gradle"))
            .firstOrNull { fileExists(it) }
            ?: return ModuleModel(path = modulePath, dir = dir)

        val content = readText(buildFile)

        val namespace = extractQuoted(content, "namespace") ?: extractPackageNamespace(content)
        val applicationId = extractQuoted(content, "applicationId")
        val compileSdk = extractInt(content, "compileSdk")
        val minSdk = extractSdkVersion(content, "minSdk")
        val targetSdk = extractSdkVersion(content, "targetSdk")
        val versionCode = extractInt(content, "versionCode")
        val versionName = extractQuoted(content, "versionName")

        val buildTypes = extractBuildTypes(content)
        val buildFeatures = extractBuildFeatures(content)
        val compileOptions = extractCompileOptions(content)
        val dependencies = extractDependencies(content, catalog)
        val proguardRules = extractProguardFiles(content).map { dir.resolve(it) }

        val srcMain = dir.resolve("src/main")
        val manifest = listOf(srcMain.resolve("AndroidManifest.xml"), dir.resolve("AndroidManifest.xml"))
            .firstOrNull { fileExists(it) }
        val resDir = listOf(srcMain.resolve("res"), dir.resolve("res")).firstOrNull { isDirectory(it) }
        val assetsDir = listOf(srcMain.resolve("assets"), dir.resolve("assets")).firstOrNull { isDirectory(it) }

        val javaSourceDirs = listOf(srcMain.resolve("java"), srcMain.resolve("src"), dir.resolve("src"))
            .filter { isDirectory(it) }
        val kotlinSourceDirs = listOf(srcMain.resolve("kotlin"), srcMain.resolve("src/kotlin"))
            .filter { isDirectory(it) }

        return ModuleModel(
            path = modulePath,
            dir = dir,
            plugin = extractPlugin(content),
            namespace = namespace,
            applicationId = applicationId,
            compileSdk = compileSdk,
            minSdk = minSdk,
            targetSdk = targetSdk,
            versionCode = versionCode,
            versionName = versionName,
            buildTypes = buildTypes,
            buildFeatures = buildFeatures,
            compileOptions = compileOptions,
            dependencies = dependencies,
            manifest = manifest,
            resDir = resDir,
            assetsDir = assetsDir,
            javaSourceDirs = javaSourceDirs,
            kotlinSourceDirs = kotlinSourceDirs,
            proguardRules = proguardRules
        )
    }

    // ---- settings parsing ----

    private fun detectModules(settings: String?, root: Path): List<String> {
        if (settings.isNullOrBlank()) {
            return if (isDirectory(root.resolve("src"))) listOf(":root") else emptyList()
        }
        val includes = Regex("""include\s*\(?\s*([^)]*)\)""", RegexOption.DOT_MATCHES_ALL).findAll(settings)
        val modules = mutableListOf<String>()
        for (match in includes) {
            Regex("""['"]([^'"]+)['"]""").findAll(match.groupValues[1]).forEach { m ->
                modules += m.groupValues[1]
            }
        }
        return modules.distinct().ifEmpty {
            if (isDirectory(root.resolve("src"))) listOf(":root") else emptyList()
        }
    }

    private fun detectRepositories(settings: String?): List<String> {
        if (settings.isNullOrBlank()) return DEFAULT_REPOSITORIES
        val urls = mutableListOf<String>()
        Regex("""['"](https?://[^'"]+)['"]""").findAll(settings).forEach { m ->
            val url = m.groupValues[1]
            if (url.contains("google") || url.contains("maven") || url.contains("jcenter")) {
                urls += url
            }
        }
        return urls.distinct().ifEmpty { DEFAULT_REPOSITORIES }
    }

    private fun extractRootProjectName(settings: String?): String? {
        if (settings.isNullOrBlank()) return null
        val match = Regex("rootProject\\.name\\s*=\\s*['\"]([^'\"]+)['\"]").find(settings)
        return match?.groupValues?.get(1)
    }

    // ---- build.gradle parsing ----

    private fun extractPlugin(content: String): String? {
        // id 'com.example' OR id("com.example") OR alias(libs.plugins.xxx)
        val direct = Regex("""id\s*\(?\s*['"]([^'"]+)['"]""").find(content)?.groupValues?.get(1)
        if (direct != null) return direct
        // Plugin alias from catalog
        val alias = Regex("""alias\s*\(\s*libs\.plugins\.([a-zA-Z0-9_.-]+)\s*\)""").find(content)?.groupValues?.get(1)
        return alias
    }

    private fun extractQuoted(content: String, key: String): String? {
        // Groovy: key 'value' OR key "value"
        // Kotlin DSL: key = "value" OR key = 'value'
        val pattern = Pattern.compile("""\b$key\s*=?\s*['"]([^'"]+)['"]""")
        val matcher = pattern.matcher(content)
        return if (matcher.find()) matcher.group(1) else null
    }

    private fun extractInt(content: String, key: String): Int? {
        // Groovy: key 34 OR key = 34
        val pattern = Pattern.compile("""\b$key\s*=?\s+(\d+)""")
        val matcher = pattern.matcher(content)
        return if (matcher.find()) matcher.group(1).toIntOrNull() else null
    }

    private fun extractSdkVersion(content: String, key: String): Int? {
        // Groovy: minSdkVersion 24 OR minSdk 24
        // Kotlin DSL: minSdk = 24
        val pattern = Pattern.compile("""\b$key(?:Version)?\s*=?\s+(\d+)""")
        val matcher = pattern.matcher(content)
        return if (matcher.find()) matcher.group(1).toIntOrNull() else null
    }

    private fun extractPackageNamespace(content: String): String? {
        return extractQuoted(content, "package")
    }

    private fun extractBuildTypes(content: String): List<String> {
        val types = mutableListOf<String>()
        val block = Regex("buildTypes\\s*\\{(.*?)\\}", RegexOption.DOT_MATCHES_ALL).find(content)
        if (block != null) {
            Regex("""\b(debug|release|staging|beta|alpha)\b""").findAll(block.groupValues[1])
                .forEach { types += it.groupValues[1] }
        }
        return types.distinct()
    }

    private fun extractBuildFeatures(content: String): Map<String, Boolean> {
        val result = mutableMapOf<String, Boolean>()
        val block = Regex("buildFeatures\\s*\\{(.*?)\\}", RegexOption.DOT_MATCHES_ALL).find(content)
        if (block != null) {
            // Groovy: compose true | Kotlin DSL: compose = true
            Regex("""\b(viewBinding|dataBinding|compose|buildConfig|aidl|renderScript)\s*=?\s*(true|false)""")
                .findAll(block.groupValues[1]).forEach { m ->
                    result[m.groupValues[1]] = m.groupValues[2] == "true"
                }
        }
        return result
    }

    private fun extractCompileOptions(content: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val block = Regex("compileOptions\\s*\\{(.*?)\\}", RegexOption.DOT_MATCHES_ALL).find(content)
        if (block != null) {
            Regex("""\b(sourceCompatibility|targetCompatibility)\s+(JavaVersion\.\w+|\w+)""")
                .findAll(block.groupValues[1]).forEach { m ->
                    result[m.groupValues[1]] = m.groupValues[2].removePrefix("JavaVersion.VERSION_")
                }
        }
        return result
    }

    private fun extractDependencies(content: String, catalog: VersionCatalogParser.Catalog = VersionCatalogParser.Catalog()): List<String> {
        val notations = mutableListOf<String>()
        val configs = listOf("implementation", "api", "compileOnly", "runtimeOnly", "annotationProcessor", "kapt", "debugImplementation", "releaseImplementation", "platform", "testImplementation", "androidTestImplementation")
        val configPattern = configs.joinToString("|")

        // Form 1: configuration 'group:artifact:version' or configuration("group:artifact:version")
        val directPattern = Pattern.compile(
            """\b(?:$configPattern)\s*\(?\s*['"]([a-zA-Z0-9_.-]+:[a-zA-Z0-9_.-]+:[0-9a-zA-Z._-]+(?:@[a-z]+)?)['"]"""
        )
        val directMatcher = directPattern.matcher(content)
        while (directMatcher.find()) {
            notations += directMatcher.group(1)
        }

        // Form 2: configuration(libs.xxx.yyy) or configuration libs.xxx.yyy — version catalog references
        // Handles both parenthesized and non-parenthesized forms
        val catalogPattern = Pattern.compile(
            """\b(?:$configPattern)\s*\(?\s*libs\.([a-zA-Z0-9_.-]+)\s*\)?"""
        )
        val catalogMatcher = catalogPattern.matcher(content)
        while (catalogMatcher.find()) {
            val alias = catalogMatcher.group(1)
            resolveCatalogAlias(alias, catalog)?.let { notations += it }
        }

        // Form 3: platform(libs.xxx) for BOM — also resolve
        val platformBomPattern = Pattern.compile(
            """\bplatform\s*\(\s*libs\.([a-zA-Z0-9_.-]+)\s*\)"""
        )
        val platformMatcher = platformBomPattern.matcher(content)
        while (platformMatcher.find()) {
            val alias = platformMatcher.group(1)
            resolveCatalogAlias(alias, catalog)?.let { notations += it }
        }

        return notations.distinct()
    }

    /**
     * Resolve a catalog alias (e.g., "androidx.core.ktx") to its Maven notation.
     * Catalog aliases use dot-separated names that map to library entries.
     */
    private fun resolveCatalogAlias(alias: String, catalog: VersionCatalogParser.Catalog): String? {
        // Direct match: alias matches a library key exactly
        catalog.libraries[alias]?.let { return it.toNotation() }

        // Try matching with normalized key (replace hyphens with dots and vice versa)
        val normalizedAlias = alias.replace('-', '.')
        catalog.libraries[normalizedAlias]?.let { return it.toNotation() }

        // Try with hyphens
        val hyphenAlias = alias.replace('.', '-')
        catalog.libraries[hyphenAlias]?.let { return it.toNotation() }

        // Fuzzy match: find a library whose key ends with the alias or a segment of it
        val aliasParts = alias.split('.')
        if (aliasParts.size > 1) {
            val lastPart = aliasParts.last()
            for ((key, ref) in catalog.libraries) {
                if (key.endsWith(lastPart) || key.endsWith(alias.replace('.', '-'))) {
                    return ref.toNotation()
                }
            }
        }

        logger.warn("Could not resolve catalog alias", mapOf("alias" to alias))
        return null
    }

    private fun extractProguardFiles(content: String): List<String> {
        val result = mutableListOf<String>()
        Regex("""'([^']*proguard[^']*)'|"([^"]*proguard[^"]*)""")
            .findAll(content).forEach { m ->
                val file = m.groupValues[1].ifBlank { m.groupValues[2] }
                if (file.isNotBlank()) result += file
            }
        return result.distinct()
    }

    // ---- properties ----

    private fun readProperties(path: Path): Map<String, String> {
        if (!fileExists(path)) return emptyMap()
        val result = mutableMapOf<String, String>()
        readText(path).lines().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isBlank() || trimmed.startsWith("#")) return@forEach
            val idx = trimmed.indexOf('=')
            if (idx > 0) {
                result[trimmed.substring(0, idx).trim()] = trimmed.substring(idx + 1).trim()
            }
        }
        return result
    }

    // ---- filesystem helpers ----

    private fun readText(path: Path): String = String(fileSystem.readAllBytes(path))

    private fun readTextIfExists(paths: List<Path>): String? {
        for (path in paths) if (fileExists(path)) return readText(path)
        return null
    }

    private fun fileExists(path: Path): Boolean = fileSystem.exists(path)

    private fun isDirectory(path: Path): Boolean = fileSystem.exists(path) && fileSystem.metadata(path).isDirectory

    private fun rel(path: Path?, root: Path): String {
        if (path == null) return "(none)"
        return try {
            root.relativize(path).toString()
        } catch (_: Exception) {
            path.toString()
        }
    }

    companion object {
        val DEFAULT_REPOSITORIES = listOf(
            "https://dl.google.com/dl/android/maven2/",
            "https://repo1.maven.org/maven2/"
        )
    }
}
