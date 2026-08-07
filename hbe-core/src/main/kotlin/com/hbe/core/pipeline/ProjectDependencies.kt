package com.hbe.core.pipeline

import java.nio.file.Path

/**
 * Resolved external dependencies for a project build: classpath entries
 * (plain jars and AAR classes.jar), library resource directories, assets,
 * native libraries, and the application package namespace.
 */
data class ProjectDependencies(
    val classpath: List<Path> = emptyList(),
    val libraryResDirs: List<Path> = emptyList(),
    val libraryAssets: List<Path> = emptyList(),
    val nativeLibs: List<Path> = emptyList(),
    val namespace: String? = null
)
