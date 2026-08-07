package com.hbe.dependency

import com.hbe.api.*
import com.hbe.api.dto.MavenCoordinate
import com.hbe.api.exception.DependencyException
import com.hbe.api.exception.NetworkException
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * Maven dependency resolver.
 *
 * Fetches POMs from the configured repositories, builds the transitive
 * closure (parent POM chain included), applies Gradle-style conflict
 * resolution (highest version wins), filters by scope (compile/runtime)
 * and honors exclusions. Artifacts are downloaded into a local cache and
 * AARs are extracted into their constituent parts.
 *
 * `file://` repository URLs are supported for offline resolution.
 */
class DependencyManagerImpl(
    private val fileSystem: FileSystem,
    private val networkClient: NetworkClient,
    private val cacheManager: CacheManager,
    private val logger: Logger
) : DependencyManager {

    override fun resolve(roots: Set<MavenCoordinate>, repositories: List<String>): DependencyGraph {
        logger.info("Resolving dependencies", mapOf(
            "count" to roots.size.toString(),
            "roots" to roots.joinToString(",") { it.toNotation() }
        ))

        val nodes = mutableMapOf<String, DependencyGraph.DependencyNode>()
        roots.forEach { root ->
            // Roots carry no packaging info; fetch their POM to learn the real
            // artifact type (aar vs jar) and the effective version.
            val pom = fetchPom(root, repositories)
            val coord = pom?.coordinates?.let { it.copy(groupId = it.groupId.ifBlank { root.groupId }) }
                ?: root
            nodes[coord.toNotation()] = DependencyGraph.DependencyNode(coordinate = coord)
        }

        // BFS over the dependency graph, resolving each node's edges from its POM.
        val queue = ArrayDeque(nodes.values.toList())
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node.dependencies.isNotEmpty()) continue

            val pom = fetchPom(node.coordinate, repositories)
            // Adopt the POM's packaging so aar artifacts download as .aar, not .jar.
            val resolvedNode = pom?.coordinates?.let { pomCoord ->
                node.copy(coordinate = node.coordinate.copy(
                    extension = pomCoord.extension ?: node.coordinate.extension
                ))
            } ?: node
            val edges = mutableListOf<DependencyGraph.DependencyEdge>()
            for (dep in pom?.dependencies ?: emptyList()) {
                if (!includedScope(dep.scope)) continue
                if (dep.optional == true) continue
                if (isExcluded(resolvedNode, dep)) continue
                val targetCoord = dep.coordinate.version?.takeIf { it.isNotBlank() }
                    ?.let { dep.coordinate }
                    ?: continue // no version and no dependencyManagement support: skip

                val targetKey = targetCoord.toNotation()
                val existing = nodes[targetKey]
                val target = existing ?: DependencyGraph.DependencyNode(coordinate = targetCoord)
                nodes[targetKey] = target
                edges += DependencyGraph.DependencyEdge(target = target, scope = dep.scope ?: "compile")
                if (existing == null) queue.addLast(target)
            }
            nodes[resolvedNode.coordinate.toNotation()] = resolvedNode.copy(dependencies = edges)
        }

        // Conflict resolution: keep the highest version for every group:artifact.
        val grouped = nodes.values.groupBy { "${it.coordinate.groupId}:${it.coordinate.artifactId}" }
        val winnerByKey = grouped.mapValues { (_, group) ->
            group.maxWithOrNull(compareBy { it.coordinate.version }) ?: group.first()
        }

        // Remap every winner node's edges to winners so the whole graph is
        // consistent (not just the roots). Build a lookup so edges reference
        // the remapped copies, not the original un-remapped nodes.
        val remappedList = winnerByKey.values.map { node ->
            node.dependencies.mapNotNull { edge ->
                winnerByKey["${edge.target.coordinate.groupId}:${edge.target.coordinate.artifactId}"]
                    ?.let { edge.scope to it }
            }
        }
        val remapped = winnerByKey.values.mapIndexed { i, node ->
            val edges = remappedList[i].map { (scope, target) ->
                DependencyGraph.DependencyEdge(target = target, scope = scope)
            }
            node.copy(dependencies = edges)
        }
        val byNotation = remapped.associateBy { it.coordinate.toNotation() }
        val consistent = remapped.map { node ->
            val edges = node.dependencies.mapNotNull { edge ->
                byNotation[edge.target.coordinate.toNotation()]
                    ?.let { DependencyGraph.DependencyEdge(target = it, scope = edge.scope) }
            }
            node.copy(dependencies = edges)
        }

        val finalNodes = consistent
            .filter { node -> roots.any { sameArtifact(it, node.coordinate) } || roots.isEmpty() }

        return DependencyGraph(roots = finalNodes)
    }

    private fun sameArtifact(a: MavenCoordinate, b: MavenCoordinate): Boolean =
        a.groupId == b.groupId && a.artifactId == b.artifactId && a.version == b.version

    override fun resolveAndDownload(coordinate: MavenCoordinate, repositories: List<String>): Path {
        val coord = effectiveCoordinates(coordinate, repositories)

        // Try the declared extension first, then fall back to the alternate so a
        // missed packaging detection (stale edge reference, POM fetch failure)
        // still resolves: jar <-> aar.
        val extensions = listOf(coord.effectiveExtension).plus(
            if (coord.effectiveExtension == "aar") "jar" else "aar"
        ).distinct()

        for (ext in extensions) {
            val candidate = coord.copy(extension = ext)
            val localPath = localPathFor(candidate)
            if (fileSystem.exists(localPath)) return localPath

            val urls = artifactUrls(candidate, repositories)
            if (urls.isEmpty()) continue

            logger.info("Downloading dependency", mapOf(
                "coordinate" to candidate.toNotation(),
                "extension" to ext
            ))

            fileSystem.createDirectories(localPath.parent)
            var lastError: Exception? = null
            for (url in urls) {
                // Download to a temp file first so a failed/partial download never
                // poisons the cache: a 404 body written directly to localPath would
                // otherwise be mistaken for a valid artifact on the next build.
                val tmp = fileSystem.createTempFile("hbe-dl-", ".tmp")
                try {
                    if (url.startsWith("file:")) {
                        val source = Path.of(url.removePrefix("file://").removePrefix("file:"))
                        fileSystem.copy(source, tmp)
                    } else {
                        networkClient.download(url, tmp)
                    }
                    fileSystem.move(tmp, localPath)
                    return localPath
                } catch (e: Exception) {
                    lastError = e
                    logger.debug("Download failed, trying next", mapOf("url" to url))
                    try { fileSystem.delete(tmp) } catch (_: Exception) {}
                }
            }
        }

        throw DependencyException(
            message = "Failed to download ${coord.toNotation()}",
            suggestion = "Check network access and repository configuration",
            coordinate = coord.toNotation()
        )
    }

    override fun extractAar(coordinate: MavenCoordinate, localPath: Path): AarContents {
        val extractDir = aarExtractDir(coordinate)
        if (fileSystem.exists(extractDir.resolve("classes.jar"))) {
            return existingAarContents(extractDir)
        }

        fileSystem.createDirectories(extractDir)
        try {
            ZipFile(localPath.toFile()).use { zf ->
                val entries = zf.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val target = extractDir.resolve(entry.name).normalize()
                    if (entry.isDirectory) {
                        Files.createDirectories(target)
                    } else {
                        Files.createDirectories(target.parent)
                        zf.getInputStream(entry).use { ins ->
                            Files.copy(ins, target, StandardCopyOption.REPLACE_EXISTING)
                        }
                    }
                }
            }
        } catch (e: IOException) {
            throw DependencyException(
                message = "Failed to extract AAR ${coordinate.toNotation()}",
                coordinate = coordinate.toNotation()
            )
        }

        return existingAarContents(extractDir)
    }

    override fun findTransitiveDependencies(node: DependencyGraph.DependencyNode): Set<DependencyGraph.DependencyEdge> {
        return node.dependencies.toSet()
    }

    // ---- internals ----

    private fun existingAarContents(extractDir: Path): AarContents {
        val classesJar = extractDir.resolve("classes.jar")
        val manifest = extractDir.resolve("AndroidManifest.xml").takeIf { fileSystem.exists(it) }
        val rTxt = extractDir.resolve("R.txt").takeIf { fileSystem.exists(it) }
        val resDir = extractDir.resolve("res").takeIf { isDirectory(it) }
        val assetsDir = extractDir.resolve("assets").takeIf { isDirectory(it) }
        val libsDir = extractDir.resolve("libs").takeIf { isDirectory(it) }
        val proguard = extractDir.resolve("proguard.txt").takeIf { fileSystem.exists(it) }
        return AarContents(
            extractDir = extractDir,
            classesJar = classesJar,
            manifest = manifest,
            rTxt = rTxt,
            resDir = resDir,
            assetsDir = assetsDir,
            libsDir = libsDir,
            proguardTxt = proguard
        )
    }

    private fun localPathFor(coordinate: MavenCoordinate): Path {
        val cacheRoot = dependencyCacheRoot()
        val groupPath = coordinate.groupId.replace('.', '/')
        return cacheRoot.resolve(groupPath).resolve(coordinate.artifactId)
            .resolve(coordinate.version)
            .resolve("${coordinate.artifactId}-${coordinate.version}" +
                (coordinate.classifier?.let { "-$it" } ?: "") + "." + coordinate.effectiveExtension)
    }

    private fun aarExtractDir(coordinate: MavenCoordinate): Path {
        val cacheRoot = dependencyCacheRoot()
        return cacheRoot.resolve(coordinate.groupId.replace('.', '/'))
            .resolve(coordinate.artifactId)
            .resolve(coordinate.version)
            .resolve("extracted")
    }

    private fun dependencyCacheRoot(): Path {
        val userHome = System.getProperty("user.home") ?: "."
        return Path.of(userHome, ".hbe", "dependencies")
    }

    private fun fetchPom(coordinate: MavenCoordinate, repositories: List<String>): PomData? {
        val raw = fetchRawPom(coordinate, repositories) ?: return null

        var result = raw
        var parent = raw.parent
        var guard = 0
        while (parent != null && guard++ < 8) {
            val parentPom = fetchRawPom(parent.coordinates, repositories) ?: break
            result = result.copy(
                coordinates = result.coordinates.copy(
                    groupId = if (result.coordinates.groupId.isBlank()) parent.coordinates.groupId else result.coordinates.groupId,
                    version = if (result.coordinates.version.isBlank()) parent.coordinates.version else result.coordinates.version
                )
            )
            parent = parentPom.parent
        }
        return result
    }

    private fun fetchRawPom(coordinate: MavenCoordinate, repositories: List<String>): PomData? {
        for (url in pomUrls(coordinate, repositories)) {
            val body = try {
                if (url.startsWith("file:")) {
                    val source = Path.of(url.removePrefix("file://").removePrefix("file:"))
                    if (!fileSystem.exists(source)) continue
                    String(fileSystem.readAllBytes(source))
                } else {
                    val response = networkClient.get(url)
                    if (!response.isSuccess) continue
                    response.bodyAsString
                }
            } catch (e: Exception) {
                logger.warn("Failed to fetch POM", mapOf("url" to url, "error" to (e.message ?: "")))
                continue
            }
            return parsePom(body)
        }
        return null
    }

    private fun parsePom(xml: String): PomData {
        val factory = DocumentBuilderFactory.newInstance()
        factory.setNamespaceAware(false)
        val doc = factory.newDocumentBuilder().parse(org.xml.sax.InputSource(StringReader(xml)))
        val root = doc.documentElement

        fun text(tag: String): String {
            val el = firstChild(root, tag) ?: return ""
            return el.textContent?.trim() ?: ""
        }

        val groupId = text("groupId")
        val artifactId = text("artifactId")
        val version = normalizeVersion(text("version"))
        val packaging = text("packaging").ifBlank { "jar" }

        val parentEl = firstChild(root, "parent")
        val parent = parentEl?.let {
            PomData(
                coordinates = MavenCoordinate(
                    groupId = childText(it, "groupId"),
                    artifactId = childText(it, "artifactId"),
                    version = normalizeVersion(childText(it, "version"))
                )
            )
        }

        val dependencies = mutableListOf<PomDependency>()
        val depsEl = firstChild(root, "dependencies")
        depsEl?.let { deps ->
            childElements(deps, "dependency").forEach { dep ->
                val exclusions = mutableListOf<MavenCoordinate>()
                firstChild(dep, "exclusions")?.let { exs ->
                    childElements(exs, "exclusion").forEach { ex ->
                        exclusions += MavenCoordinate(
                            groupId = childText(ex, "groupId").takeIf { it.isNotBlank() } ?: "*",
                            artifactId = childText(ex, "artifactId").takeIf { it.isNotBlank() } ?: "*",
                            version = "*"
                        )
                    }
                }
                dependencies += PomDependency(
                    coordinate = MavenCoordinate(
                        groupId = childText(dep, "groupId"),
                        artifactId = childText(dep, "artifactId"),
                        version = normalizeVersion(childText(dep, "version"))
                    ),
                    scope = childText(dep, "scope").ifBlank { "compile" },
                    optional = childText(dep, "optional") == "true",
                    excludes = exclusions
                )
            }
        }

        return PomData(
            coordinates = MavenCoordinate(
                groupId = groupId,
                artifactId = artifactId,
                version = version,
                extension = packaging.takeIf { it == "aar" }
            ),
            dependencies = dependencies,
            parent = parent
        )
    }

    private fun normalizeVersion(version: String): String {
        var v = version.trim()
        // Gradle hard-version syntax in POMs: [1.6.1] or [1.6.1,2.0)
        while (v.startsWith("[") && v.contains("]")) {
            v = v.removePrefix("[").substringBefore(",").substringBefore("]")
        }
        while (v.startsWith("(") && v.contains(")")) {
            v = v.removePrefix("(").substringBefore(",").substringBefore(")")
        }
        return v.trim()
    }

    private fun effectiveCoordinates(coordinate: MavenCoordinate, repositories: List<String>): MavenCoordinate {
        return coordinate
    }

    private fun pomUrls(coordinate: MavenCoordinate, repositories: List<String>): List<String> {
        val base = coordinate.groupId.replace('.', '/') + "/" +
            coordinate.artifactId + "/" + coordinate.version + "/" +
            coordinate.artifactId + "-" + coordinate.version
        val urls = repositories.map { it.trimEnd('/') + "/" + base + ".pom" }
        return if (urls.isEmpty()) emptyList() else urls
    }

    private fun artifactUrls(coordinate: MavenCoordinate, repositories: List<String>): List<String> {
        val base = coordinate.groupId.replace('.', '/') + "/" +
            coordinate.artifactId + "/" + coordinate.version + "/" +
            coordinate.artifactId + "-" + coordinate.version +
            (coordinate.classifier?.let { "-$it" } ?: "") + "." + coordinate.effectiveExtension
        val urls = repositories.map { it.trimEnd('/') + "/" + base }
        return if (urls.isEmpty()) emptyList() else urls
    }

    private fun includedScope(scope: String?): Boolean {
        return when (scope) {
            null, "", "compile", "runtime" -> true
            else -> false
        }
    }

    private fun isExcluded(node: DependencyGraph.DependencyNode, dep: PomDependency): Boolean {
        return node.excludes.any { ex ->
            (ex.groupId == "*" || ex.groupId == dep.coordinate.groupId) &&
                (ex.artifactId == "*" || ex.artifactId == dep.coordinate.artifactId)
        }
    }

    private fun isDirectory(path: Path): Boolean {
        return fileSystem.exists(path) && fileSystem.metadata(path).isDirectory
    }

    // ---- XML helpers ----

    private fun firstChild(parent: Element, tag: String): Element? {
        return childElements(parent, tag).firstOrNull()
    }

    private fun childElements(parent: Element, tag: String): List<Element> {
        val result = mutableListOf<Element>()
        val children = parent.childNodes
        for (i in 0 until children.length) {
            val node = children.item(i)
            if (node is Element && node.tagName == tag) result.add(node)
        }
        return result
    }

    private fun childText(parent: Element, tag: String): String {
        return firstChild(parent, tag)?.textContent?.trim() ?: ""
    }

    private fun StringReader(value: String): java.io.StringReader = java.io.StringReader(value)

    private data class PomData(
        val coordinates: MavenCoordinate,
        val dependencies: List<PomDependency> = emptyList(),
        val parent: PomData? = null
    )

    private data class PomDependency(
        val coordinate: MavenCoordinate,
        val scope: String = "compile",
        val optional: Boolean = false,
        val excludes: List<MavenCoordinate> = emptyList()
    )
}
