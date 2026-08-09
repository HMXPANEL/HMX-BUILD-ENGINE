package com.hbe.core.project

import com.hbe.api.FileSystem
import java.nio.file.Path

/**
 * Lightweight parser for Gradle version catalogs (gradle/libs.versions.toml).
 *
 * Supports the common subset of TOML used by Android Studio:
 *   [versions]   — version aliases
 *   [libraries]  — library aliases with group/name/version or version.ref
 *   [plugins]    — plugin aliases with id and version or version.ref
 *
 * No external TOML dependency — parses only the patterns Gradle actually emits.
 */
class VersionCatalogParser(
    private val fileSystem: FileSystem
) {

    data class Catalog(
        val versions: Map<String, String> = emptyMap(),
        val libraries: Map<String, LibraryRef> = emptyMap(),
        val plugins: Map<String, PluginRef> = emptyMap()
    )

    data class LibraryRef(
        val groupId: String,
        val artifactId: String,
        val version: String? = null
    ) {
        fun toNotation(): String = "$groupId:$artifactId:${version ?: ""}"
    }

    data class PluginRef(
        val id: String,
        val version: String? = null
    )

    fun parse(catalogFile: Path): Catalog {
        if (!fileSystem.exists(catalogFile)) return Catalog()
        val text = String(fileSystem.readAllBytes(catalogFile))
        return parseText(text)
    }

    fun parseText(text: String): Catalog {
        val versions = mutableMapOf<String, String>()
        val libraries = mutableMapOf<String, LibraryRef>()
        val plugins = mutableMapOf<String, PluginRef>()

        var currentSection: String? = null

        for (rawLine in text.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) continue

            val sectionMatch = Regex("""^\[([^\]]+)\]$""").find(line)
            if (sectionMatch != null) {
                currentSection = sectionMatch.groupValues[1].trim()
                continue
            }

            when (currentSection) {
                "versions" -> {
                    val m = Regex("""^([a-zA-Z0-9_-]+)\s*=\s*["']([^"']+)["']$""").find(line)
                    if (m != null) versions[m.groupValues[1]] = m.groupValues[2]
                }
                "libraries" -> {
                    parseLibrary(line)?.let { (name, ref) -> libraries[name] = ref }
                }
                "plugins" -> {
                    parsePlugin(line)?.let { (name, ref) -> plugins[name] = ref }
                }
            }
        }

        // Resolve version.ref references
        val resolvedLibraries = libraries.mapValues { (_, ref) ->
            if (ref.version == null || !ref.version.contains(".")) {
                val resolved = versions[ref.version ?: ""] ?: ref.version
                ref.copy(version = resolved)
            } else {
                ref
            }
        }

        val resolvedPlugins = plugins.mapValues { (_, ref) ->
            if (ref.version == null || !ref.version.contains(".")) {
                val resolved = versions[ref.version ?: ""] ?: ref.version
                ref.copy(version = resolved)
            } else {
                ref
            }
        }

        return Catalog(versions, resolvedLibraries, resolvedPlugins)
    }

    private fun parseLibrary(line: String): Pair<String, LibraryRef>? {
        // Form 1: alias = { group = "...", name = "...", version.ref = "..." }
        val inlineMatch = Regex("""^([a-zA-Z0-9_-]+)\s*=\s*\{(.*)\}$""").find(line)
        if (inlineMatch != null) {
            val name = inlineMatch.groupValues[1]
            val body = inlineMatch.groupValues[2]
            val group = extractTomlValue(body, "group") ?: return null
            val artifact = extractTomlValue(body, "name") ?: return null
            val version = extractTomlValue(body, "version")?.let { v ->
                if (v.startsWith("ref:")) null else v
            }
            val versionRef = extractTomlValue(body, "version.ref")
            return name to LibraryRef(group, artifact, version ?: versionRef)
        }

        // Form 2: alias = "group:artifact:version"
        val stringMatch = Regex("""^([a-zA-Z0-9_-]+)\s*=\s*["']([^"']+)["']$""").find(line)
        if (stringMatch != null) {
            val name = stringMatch.groupValues[1]
            val notation = stringMatch.groupValues[2]
            val parts = notation.split(":")
            if (parts.size >= 3) {
                return name to LibraryRef(parts[0], parts[1], parts[2])
            } else if (parts.size == 2) {
                return name to LibraryRef(parts[0], parts[1], null)
            }
        }

        // Form 3: alias = { module = "group:artifact", version.ref = "..." }
        val moduleMatch = Regex("""^([a-zA-Z0-9_-]+)\s*=\s*\{[^}]*module\s*=\s*["']([^"']+)["'][^}]*\}$""").find(line)
        if (moduleMatch != null) {
            val name = moduleMatch.groupValues[1]
            val module = moduleMatch.groupValues[2]
            val body = moduleMatch.groupValues[0]
            val moduleParts = module.split(":")
            if (moduleParts.size >= 2) {
                val versionRef = extractTomlValue(body, "version.ref")
                return name to LibraryRef(moduleParts[0], moduleParts[1], versionRef)
            }
        }

        return null
    }

    private fun parsePlugin(line: String): Pair<String, PluginRef>? {
        // Form 1: alias = { id = "...", version.ref = "..." }
        val inlineMatch = Regex("""^([a-zA-Z0-9_-]+)\s*=\s*\{(.*)\}$""").find(line)
        if (inlineMatch != null) {
            val name = inlineMatch.groupValues[1]
            val body = inlineMatch.groupValues[2]
            val id = extractTomlValue(body, "id") ?: return null
            val version = extractTomlValue(body, "version")?.let { v ->
                if (v.startsWith("ref:")) null else v
            }
            val versionRef = extractTomlValue(body, "version.ref")
            return name to PluginRef(id, version ?: versionRef)
        }

        // Form 2: alias = "id:version"
        val stringMatch = Regex("""^([a-zA-Z0-9_-]+)\s*=\s*["']([^"']+)["']$""").find(line)
        if (stringMatch != null) {
            val name = stringMatch.groupValues[1]
            val notation = stringMatch.groupValues[2]
            val parts = notation.split(":")
            if (parts.size >= 2) {
                return name to PluginRef(parts[0], parts[1])
            }
        }

        return null
    }

    private fun extractTomlValue(body: String, key: String): String? {
        val m = Regex("""$key\s*=\s*["']([^"']+)["']""").find(body)
        return m?.groupValues?.get(1)
    }
}
