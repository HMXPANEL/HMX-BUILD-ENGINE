package com.hbe.api

import java.nio.file.Path

interface ResourceCompiler {
    fun mergeResources(outputDir: Path, appResDir: Path, libraryResDirs: List<Path>)
    fun compile(resDir: Path, outputDir: Path): List<Path>
    fun link(
        flatFiles: List<Path>,
        manifest: Path,
        outputDir: Path,
        compileSdk: Int,
        extraPackages: List<String> = emptyList()
    ): ResourceBundle
    fun mergeManifests(manifests: List<ManifestSource>, outputDir: Path): Path
}

data class ResourceBundle(
    val resourcesArsc: Path,
    val rJava: Path,
    val compiledResDirectories: List<Path> = emptyList(),
    val manifest: Path,
    val configurations: Set<String> = emptySet(),
    val resourceIds: Map<String, Int> = emptyMap()
)

data class ManifestSource(
    val path: Path,
    val label: String = "",
    val isMain: Boolean = false
)
