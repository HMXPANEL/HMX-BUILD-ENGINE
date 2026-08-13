package com.hbe.core.project

import java.nio.file.Path

/**
 * Parsed representation of an Android project (settings + module build files,
 * source sets, build variants and dependencies) for HMX Build Engine.
 */
data class ProjectModel(
    val rootDir: Path,
    val name: String,
    val modules: List<ModuleModel>,
    val repositories: List<String>,
    val gradleProperties: Map<String, String>,
    val localProperties: Map<String, String>
) {
    val applicationModule: ModuleModel? get() = modules.firstOrNull { it.applicationId != null || it.plugin?.contains("application") == true }

    /**
     * Modules in dependency order: a module always appears after every module
     * it depends on via `project(":x")`. Libraries first, application module last.
     * Modules with no project dependencies are emitted in declaration order.
     */
    val moduleOrder: List<ModuleModel> get() {
        val byPath = modules.associateBy { it.path }
        val visited = mutableSetOf<String>()
        val result = mutableListOf<ModuleModel>()

        fun visit(m: ModuleModel) {
            if (m.path in visited) return
            visited.add(m.path)
            for (dep in m.projectDependencies) {
                byPath[dep]?.let { visit(it) }
            }
            result.add(m)
        }

        for (m in modules) visit(m)
        return result
    }
}

data class ModuleModel(
    val path: String,
    val dir: Path,
    val plugin: String? = null,
    val namespace: String? = null,
    val applicationId: String? = null,
    val compileSdk: Int? = null,
    val minSdk: Int? = null,
    val targetSdk: Int? = null,
    val versionCode: Int? = null,
    val versionName: String? = null,
    val buildTypes: List<String> = emptyList(),
    val buildFeatures: Map<String, Boolean> = emptyMap(),
    val compileOptions: Map<String, String> = emptyMap(),
    val dependencies: List<String> = emptyList(),
    val projectDependencies: List<String> = emptyList(),
    val manifest: Path? = null,
    val resDir: Path? = null,
    val assetsDir: Path? = null,
    val javaSourceDirs: List<Path> = emptyList(),
    val kotlinSourceDirs: List<Path> = emptyList(),
    val proguardRules: List<Path> = emptyList(),
    val sourceSets: Map<String, List<String>> = emptyMap()
) {
    val isAndroidModule: Boolean get() = plugin?.contains("android") == true
}
