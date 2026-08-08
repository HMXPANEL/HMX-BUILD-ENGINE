package com.hbe.core.project

import java.nio.file.Path

/**
 * Lightweight helpers for extracting Android/Gradle project metadata without
 * running a full project import. Used by the build pipelines to inject
 * namespace/minSdk/version into the manifest.
 */
object GradleMetadata {

    /**
     * Scans build.gradle files under [projectDir] for a `namespace` declaration.
     * Returns the first match, or null if not found.
     */
    fun findNamespace(projectDir: Path): String? {
        val candidates = listOf(
            projectDir.resolve("app/build.gradle"),
            projectDir.resolve("app/build.gradle.kts"),
            projectDir.resolve("build.gradle"),
            projectDir.resolve("build.gradle.kts")
        )
        for (file in candidates) {
            if (!java.nio.file.Files.exists(file)) continue
            val text = runCatching { String(java.nio.file.Files.readAllBytes(file)) }.getOrDefault("")
            // namespace 'com.example' or namespace "com.example"
            val m = Regex("""namespace\s+['"]([^'"]+)['"]""").find(text)
            if (m != null) return m.groupValues[1]
        }
        return null
    }

    /**
     * Scans build.gradle files for `minSdk` / `minSdkVersion`.
     */
    fun findMinSdk(projectDir: Path): Int? {
        val candidates = listOf(
            projectDir.resolve("app/build.gradle"),
            projectDir.resolve("app/build.gradle.kts")
        )
        for (file in candidates) {
            if (!java.nio.file.Files.exists(file)) continue
            val text = runCatching { String(java.nio.file.Files.readAllBytes(file)) }.getOrDefault("")
            val m = Regex("""minSdk(?:Version)?\s+(\d+)""").find(text)
            if (m != null) return m.groupValues[1].toIntOrNull()
        }
        return null
    }
}
