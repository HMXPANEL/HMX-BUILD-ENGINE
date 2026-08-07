package com.hbe.core.project

import com.hbe.api.AarContents
import com.hbe.api.DependencyManager
import com.hbe.api.DependencyGraph
import com.hbe.api.Logger
import com.hbe.api.dto.MavenCoordinate
import com.hbe.api.exception.DependencyException
import com.hbe.core.pipeline.ProjectDependencies
import java.nio.file.Files
import java.nio.file.Path

/**
 * Resolves a project's declared dependencies through the [DependencyManager]
 * into the artifacts a build pipeline consumes: compile classpath, library
 * resources, assets and native libraries.
 */
class ProjectResolver(
    private val dependencyManager: DependencyManager,
    private val logger: Logger
) {

    fun resolve(model: ProjectModel, modulePath: String = ":app"): ProjectDependencies {
        val module = model.modules.firstOrNull { it.path == modulePath }
            ?: model.applicationModule
            ?: throw DependencyException(
                message = "No module to resolve dependencies for",
                coordinate = modulePath
            )

        val repositories = (model.repositories + DependencyManager.defaultRepos).distinct()
        val roots = module.dependencies.mapNotNull { parseCoordinate(it) }.toSet()

        if (roots.isEmpty()) {
            return ProjectDependencies(namespace = module.namespace)
        }

        logger.info("Resolving project dependencies", mapOf(
            "module" to module.path,
            "roots" to roots.joinToString(",") { it.toNotation() }
        ))

        val graph = dependencyManager.resolve(roots, repositories)

        val classpath = mutableListOf<Path>()
        val libraryResDirs = mutableListOf<Path>()
        val libraryAssets = mutableListOf<Path>()
        val nativeLibs = mutableListOf<Path>()

        collectArtifacts(graph, repositories, classpath, libraryResDirs, libraryAssets, nativeLibs)

        return ProjectDependencies(
            classpath = classpath.distinct().sorted(),
            libraryResDirs = libraryResDirs.distinct().sorted(),
            libraryAssets = libraryAssets.distinct().sorted(),
            nativeLibs = nativeLibs.distinct().sorted(),
            namespace = module.namespace
        )
    }

    private fun collectArtifacts(
        graph: DependencyGraph,
        repositories: List<String>,
        classpath: MutableList<Path>,
        libraryResDirs: MutableList<Path>,
        libraryAssets: MutableList<Path>,
        nativeLibs: MutableList<Path>
    ) {
        // Collect every reachable node, then keep only the highest version for each
        // group:artifact. Leaky conflict resolution (or transitive duplicates) can
        // otherwise place multiple versions on the classpath; javac uses the first
        // one it sees, so an older copy shadowing a newer one breaks compilation
        // (e.g. core:1.0.0 hiding core:1.9.0's setSilent(boolean)).
        val allNodes = mutableListOf<DependencyGraph.DependencyNode>()
        val seen = mutableSetOf<String>()
        val queue = ArrayDeque(graph.roots.toList())
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (!seen.add(node.coordinate.toNotation())) continue
            allNodes += node
            queue.addAll(node.dependencies.map { it.target })
        }

        val winnerByKey = LinkedHashMap<String, DependencyGraph.DependencyNode>()
        for (node in allNodes) {
            val key = "${node.coordinate.groupId}:${node.coordinate.artifactId}"
            val existing = winnerByKey[key]
            if (existing == null || compareVersions(node.coordinate.version, existing.coordinate.version) > 0) {
                winnerByKey[key] = node
            }
        }

        for (node in winnerByKey.values) {
            addArtifact(node.coordinate, repositories, classpath, libraryResDirs, libraryAssets, nativeLibs)
        }
    }

    /**
     * Compares two dot-separated version strings segment by segment. Returns >0 if
     * a is newer, <0 if b is newer, 0 if equal. Falls back to lexical comparison for
     * non-numeric suffixes (e.g. "-rc1", "alpha").
     */
    private fun compareVersions(a: String, b: String): Int {
        val pa = a.split(".")
        val pb = b.split(".")
        val n = maxOf(pa.size, pb.size)
        for (i in 0 until n) {
            val sa = pa.getOrElse(i) { "0" }
            val sb = pb.getOrElse(i) { "0" }
            val na = sa.toIntOrNull()
            val nb = sb.toIntOrNull()
            if (na != null && nb != null) {
                if (na != nb) return na - nb
            } else {
                val c = sa.compareTo(sb)
                if (c != 0) return c
            }
        }
        return 0
    }

    private fun addArtifact(
        coordinate: MavenCoordinate,
        repositories: List<String>,
        classpath: MutableList<Path>,
        libraryResDirs: MutableList<Path>,
        libraryAssets: MutableList<Path>,
        nativeLibs: MutableList<Path>
    ) {
        val local = dependencyManager.resolveAndDownload(coordinate, repositories)
        if (coordinate.effectiveExtension == "aar") {
            val contents = dependencyManager.extractAar(coordinate, local)
            if (contents.classesJar != null && Files.exists(contents.classesJar)) classpath.add(contents.classesJar)
            val resDir = contents.resDir
            if (resDir != null) libraryResDirs.add(resDir)
            val assetsDir = contents.assetsDir
            if (assetsDir != null) libraryAssets.add(assetsDir)
            val libsDir = contents.libsDir
            if (libsDir != null) nativeLibs.add(libsDir)
            val jni = contents.extractDir.resolve("jni")
            if (Files.isDirectory(jni)) nativeLibs.add(jni)
        } else {
            classpath.add(local)
        }
    }

    fun parseCoordinate(notation: String): MavenCoordinate? {
        val parts = notation.removePrefix("implementation").trim().split(":")
        if (parts.size < 3) return null
        return MavenCoordinate(
            groupId = parts[0].trim(),
            artifactId = parts[1].trim(),
            version = parts[2].trim(),
            extension = parts.getOrNull(3)?.takeIf { it == "aar" }
        )
    }
}
