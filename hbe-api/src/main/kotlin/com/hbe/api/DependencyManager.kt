package com.hbe.api

import com.hbe.api.dto.MavenCoordinate

interface DependencyManager {
    fun resolve(roots: Set<MavenCoordinate>, repositories: List<String>): DependencyGraph
    fun extractAar(coordinate: MavenCoordinate, localPath: java.nio.file.Path): AarContents
    fun resolveAndDownload(coordinate: MavenCoordinate, repositories: List<String> = defaultRepos): java.nio.file.Path
    fun findTransitiveDependencies(node: DependencyGraph.DependencyNode): Set<DependencyGraph.DependencyEdge>

    companion object {
        val defaultRepos = listOf(
            "https://dl.google.com/dl/android/maven2/",
            "https://repo1.maven.org/maven2/"
        )
    }
}

data class DependencyGraph(
    val roots: List<DependencyNode>
) {
    data class DependencyNode(
        val coordinate: MavenCoordinate,
        val dependencies: List<DependencyEdge> = emptyList(),
        val scope: String = "compile",
        val optional: Boolean = false,
        val excludes: List<MavenCoordinate> = emptyList()
    )

    data class DependencyEdge(
        val target: DependencyNode,
        val scope: String = "compile"
    )
}

data class AarContents(
    val extractDir: java.nio.file.Path,
    val classesJar: java.nio.file.Path,
    val manifest: java.nio.file.Path? = null,
    val rTxt: java.nio.file.Path? = null,
    val resDir: java.nio.file.Path? = null,
    val assetsDir: java.nio.file.Path? = null,
    val libsDir: java.nio.file.Path? = null,
    val proguardTxt: java.nio.file.Path? = null
)
