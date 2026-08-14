package com.hbe.resources

import com.hbe.api.*
import com.hbe.api.exception.ResourceException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

class ResourceCompilerImpl(
    private val sdkManager: SdkManager,
    private val fileSystem: FileSystem,
    private val toolRunner: ToolRunner,
    private val logger: Logger
) : ResourceCompiler {

    private companion object {
        const val XMLNS_NS = "http://www.w3.org/2000/xmlns/"
    }

    override fun compile(resDir: Path, outputDir: Path): List<Path> {
        if (!fileSystem.exists(resDir)) {
            logger.warn("Resource directory not found", mapOf("dir" to resDir.toString()))
            return emptyList()
        }

        val discovery = discoverResources(resDir)
        if (discovery.fileCount == 0) {
            logger.info("No resource files to compile", mapOf("dir" to resDir.toString()))
            return emptyList()
        }

        logger.info("Compiling resources", mapOf(
            "dir" to resDir.toString(),
            "files" to discovery.fileCount.toString(),
            "types" to discovery.directories.toString()
        ))

        fileSystem.createDirectories(outputDir)
        val result = toolRunner.run("aapt2", listOf(
            "compile",
            "--dir", resDir.toString(),
            "-o", outputDir.toString()
        ))
        if (!result.succeeded) {
            throw ResourceException(
                message = "aapt2 compile failed",
                suggestion = "Fix the resource compilation errors and rebuild",
                details = parseAapt2Errors(result.stderr)
            )
        }

        val flatFiles = fileSystem.walkFiles(outputDir, "*.flat")
        logger.info("Resource compilation complete", mapOf("flatFiles" to flatFiles.size.toString()))
        return flatFiles
    }

    override fun link(
        flatFiles: List<Path>,
        manifest: Path,
        outputDir: Path,
        compileSdk: Int,
        extraPackages: List<String>
    ): ResourceBundle {
        if (flatFiles.isEmpty() && !fileSystem.exists(manifest)) {
            throw ResourceException(
                message = "No resources to link and no manifest found",
                suggestion = "Ensure your project has a res/ directory and AndroidManifest.xml"
            )
        }

        val manifestInfo = parseManifest(manifest)
        logger.info("Linking resources", mapOf(
            "package" to manifestInfo.packageName,
            "activities" to manifestInfo.activities.size.toString(),
            "flatFiles" to flatFiles.size.toString(),
            "compileSdk" to compileSdk.toString()
        ))

        val resolution = sdkManager.resolveSdk(compileSdk)
        val androidJar = resolution.androidJar

        fileSystem.createDirectories(outputDir)
        val linkedApk = outputDir.resolve("linked.apk")
        val genDir = outputDir.resolve("gen")
        val symbolsFile = outputDir.resolve("symbols.txt")

        val minSdk = manifestInfo.minSdk ?: 24
        val targetSdk = manifestInfo.targetSdk ?: 34

        val args = mutableListOf(
            "link",
            "--auto-add-overlay",
            "--min-sdk-version", minSdk.toString(),
            "--target-sdk-version", targetSdk.toString(),
            "--manifest", manifest.toString(),
            "--java", genDir.toString(),
            "--output-text-symbols", symbolsFile.toString(),
            "-I", androidJar.toString(),
            "-o", linkedApk.toString()
        )
        for (pkg in extraPackages) {
            args.add("--package-id")
            args.add(pkg)
        }
        for (flat in flatFiles) {
            args.add(flat.toString())
        }

        val result = toolRunner.run("aapt2", args)
        if (!result.succeeded) {
            throw ResourceException(
                message = "aapt2 link failed",
                suggestion = "Fix the manifest or resource references and rebuild",
                details = parseAapt2Errors(result.stderr)
            )
        }

        val resourcesOut = outputDir.resolve("resources")
        extractZip(linkedApk, resourcesOut)

        val resourcesArsc = resourcesOut.resolve("resources.arsc")
        val linkedManifest = resourcesOut.resolve("AndroidManifest.xml")
        if (!Files.isRegularFile(resourcesArsc)) {
            throw ResourceException(
                message = "aapt2 link did not produce resources.arsc",
                suggestion = "This is a bug — please report it with the aapt2 output"
            )
        }

        val packageDir = manifestInfo.packageName
            .split('.')
            .filter { it.isNotBlank() }
            .fold(genDir) { acc, segment -> acc.resolve(segment) }
        val rJava = packageDir.resolve("R.java")
        val resDir = resourcesOut.resolve("res")

        return ResourceBundle(
            resourcesArsc = resourcesArsc,
            rJava = rJava,
            compiledResDirectories = if (Files.isDirectory(resDir)) listOf(resDir) else emptyList(),
            manifest = linkedManifest,
            configurations = setOf("default") + discoverResources(resDir).directories.keys,
            resourceIds = parseSymbols(symbolsFile)
        )
    }

    override fun linkProto(
        flatFiles: List<Path>,
        manifest: Path,
        outputDir: Path,
        compileSdk: Int,
        extraPackages: List<String>
    ): ResourceBundle {
        if (flatFiles.isEmpty() && !fileSystem.exists(manifest)) {
            throw ResourceException(
                message = "No resources to link and no manifest found",
                suggestion = "Ensure your project has a res/ directory and AndroidManifest.xml"
            )
        }

        val manifestInfo = parseManifest(manifest)
        val resolution = sdkManager.resolveSdk(compileSdk)
        val androidJar = resolution.androidJar

        fileSystem.createDirectories(outputDir)
        val linkedApk = outputDir.resolve("linked.apk")
        val genDir = outputDir.resolve("gen")
        val symbolsFile = outputDir.resolve("symbols.txt")

        val minSdk = manifestInfo.minSdk ?: 24
        val targetSdk = manifestInfo.targetSdk ?: 34

        val args = mutableListOf(
            "link",
            "--proto-format",
            "--auto-add-overlay",
            "--min-sdk-version", minSdk.toString(),
            "--target-sdk-version", targetSdk.toString(),
            "--manifest", manifest.toString(),
            "--java", genDir.toString(),
            "--output-text-symbols", symbolsFile.toString(),
            "-I", androidJar.toString(),
            "-o", linkedApk.toString()
        )
        for (pkg in extraPackages) {
            args.add("--package-id")
            args.add(pkg)
        }
        for (flat in flatFiles) {
            args.add(flat.toString())
        }

        val result = toolRunner.run("aapt2", args)
        if (!result.succeeded) {
            throw ResourceException(
                message = "aapt2 link (proto) failed",
                suggestion = "Fix the manifest or resource references and rebuild",
                details = parseAapt2Errors(result.stderr)
            )
        }

        val extracted = outputDir.resolve("extracted")
        fileSystem.createDirectories(extracted)
        extractZip(linkedApk, extracted)

        val resourcesPb = extracted.resolve("resources.pb")
        val linkedManifest = extracted.resolve("AndroidManifest.xml")
        val resDir = extracted.resolve("res")
        val packageDir = manifestInfo.packageName
            .split('.')
            .filter { it.isNotBlank() }
            .fold(genDir) { acc, segment -> acc.resolve(segment) }
        val rJava = packageDir.resolve("R.java")

        if (!Files.isRegularFile(resourcesPb)) {
            throw ResourceException(
                message = "aapt2 link (proto) did not produce resources.pb",
                suggestion = "This is a bug — please report it with the aapt2 output"
            )
        }

        return ResourceBundle(
            resourcesArsc = resourcesPb,
            rJava = rJava,
            compiledResDirectories = if (Files.isDirectory(resDir)) listOf(resDir) else emptyList(),
            manifest = linkedManifest,
            configurations = setOf("default") + discoverResources(resDir).directories.keys,
            resourceIds = parseSymbols(symbolsFile),
            resourcesPb = resourcesPb
        )
    }

    override fun mergeManifests(manifests: List<ManifestSource>, outputDir: Path): Path {
        if (manifests.isEmpty()) {
            throw ResourceException("No manifests to merge")
        }
        if (manifests.size == 1) return manifests[0].path

        logger.info("Merging manifests", mapOf("count" to manifests.size.toString()))

        val mainManifest = manifests.first { it.isMain }
        val mergedPath = outputDir.resolve("AndroidManifest.xml")
        fileSystem.createDirectories(outputDir)
        fileSystem.copy(mainManifest.path, mergedPath)
        return mergedPath
    }

    internal data class ManifestInfo(
        val packageName: String,
        val minSdk: Int?,
        val targetSdk: Int?,
        val activities: List<String>,
        val applicationLabel: String?
    )

    internal data class ResourceDiscovery(
        val directories: Map<String, Int>,
        val fileCount: Int
    )

    internal fun parseManifest(manifest: Path): ManifestInfo {
        val factory = DocumentBuilderFactory.newInstance()
        factory.setNamespaceAware(false)
        val builder = factory.newDocumentBuilder()
        val doc = builder.parse(manifest.toFile())
        val root = doc.documentElement

        val packageName = root.getAttribute("package")
        val application = firstElement(root, "application")
        val usesSdk = firstElement(root, "uses-sdk")

        val activities = application?.let { app ->
            childElements(app, "activity")
                .mapNotNull { it.getAttribute("android:name").ifBlank { null } }
        } ?: emptyList()

        return ManifestInfo(
            packageName = packageName,
            minSdk = usesSdk?.getAttribute("android:minSdkVersion")?.toIntOrNull(),
            targetSdk = usesSdk?.getAttribute("android:targetSdkVersion")?.toIntOrNull(),
            activities = activities,
            applicationLabel = application?.getAttribute("android:label")
        )
    }

    internal fun discoverResources(resDir: Path): ResourceDiscovery {
        if (!Files.isDirectory(resDir)) return ResourceDiscovery(emptyMap(), 0)
        val directories = mutableMapOf<String, Int>()
        var fileCount = 0
        Files.walk(resDir).use { stream ->
            stream.filter { Files.isRegularFile(it) }.forEach { file ->
                fileCount++
                val type = file.parent?.fileName?.toString() ?: "other"
                directories.merge(type, 1, Int::plus)
            }
        }
        return ResourceDiscovery(directories, fileCount)
    }

    internal fun parseSymbols(symbolsFile: Path): Map<String, Int> {
        if (!Files.isRegularFile(symbolsFile)) return emptyMap()
        val ids = mutableMapOf<String, Int>()
        Files.readAllLines(symbolsFile).forEach { line ->
            val parts = line.trim().split(" ")
            // Format: "int <type> <name> 0x<hex>" — skip int[] styleable tables
            if (parts.size == 4 && parts[0] == "int") {
                parts[3].removePrefix("0x").toIntOrNull(16)?.let { ids[parts[2]] = it }
            }
        }
        return ids
    }

    override fun mergeResources(outputDir: Path, appResDir: Path, libraryResDirs: List<Path>) {
        // App res dir first (highest priority), then libraries, so that on a
        // name collision the app's value is kept and library duplicates are
        // dropped. Among libraries, earlier entries win.
        val sources = listOf(appResDir) + libraryResDirs
        fileSystem.createDirectories(outputDir)

        val valuesDocs = mutableListOf<org.w3c.dom.Document>()
        val nsSources = mutableListOf<Path>()
        for (src in sources) {
            if (src == null || !fileSystem.exists(src)) continue
            val subdirs = fileSystem.listFiles(src, "*")
                .filter { fileSystem.metadata(it).isDirectory }
                .sortedBy { it.fileName.toString() }
            for (subdir in subdirs) {
                val dirName = subdir.fileName.toString()
                if (dirName.startsWith("values")) {
                    val xmls = fileSystem.walkFiles(subdir, "*.xml")
                    nsSources += xmls
                    for (xml in xmls) {
                        valuesDocs += parseValues(xml)
                    }
                } else {
                    val outSubdir = outputDir.resolve(dirName)
                    for (file in fileSystem.walkFiles(subdir, "*")) {
                        val rel = subdir.relativize(file)
                        val target = outSubdir.resolve(rel).normalize()
                        if (target.startsWith(outSubdir)) {
                            fileSystem.createDirectories(target.parent)
                            fileSystem.copy(file, target)
                        }
                    }
                }
            }
        }

        if (valuesDocs.isNotEmpty()) {
            // aapt2 --dir expects values resources inside a values/ subdirectory.
            writeMergedValues(outputDir.resolve("values/values.xml"), valuesDocs, nsSources)
        }
    }

    private fun parseValues(path: Path): org.w3c.dom.Document {
        val factory = DocumentBuilderFactory.newInstance()
        factory.setNamespaceAware(true)
        return factory.newDocumentBuilder().parse(path.toFile())
    }

    /**
     * Collects every xmlns / xmlns:* declaration from the root of each source
     * document. Parsed non-namespace-aware so the declarations show up as plain
     * attributes. Returns a map of namespace URI → prefix. If the same prefix is
     * used for two different URIs, the second one gets a generated unique prefix
     * so no declaration is silently dropped.
     */
    private fun collectNamespaces(sources: List<Path>): Map<String, String> {
        val factory = DocumentBuilderFactory.newInstance()
        factory.setNamespaceAware(false)
        val builder = factory.newDocumentBuilder()
        val uriToPrefix = LinkedHashMap<String, String>()
        val takenPrefixes = mutableSetOf<String>()
        for (path in sources) {
            if (!fileSystem.exists(path)) continue
            val root = builder.parse(path.toFile()).documentElement ?: continue
            for (i in 0 until root.attributes.length) {
                val attr = root.attributes.item(i)
                val name = attr.nodeName
                if (name != "xmlns" && !name.startsWith("xmlns:")) continue
                val prefix = if (name == "xmlns") "" else name.removePrefix("xmlns:")
                val uri = attr.nodeValue
                registerNamespace(uri, prefix, uriToPrefix, takenPrefixes)
            }
        }
        return uriToPrefix
    }

    private fun registerNamespace(
        uri: String,
        prefix: String,
        uriToPrefix: MutableMap<String, String>,
        takenPrefixes: MutableSet<String>
    ) {
        val existingPrefix = uriToPrefix[uri]
        if (existingPrefix != null) {
            // Same URI seen again — keep the first prefix, ignore duplicates.
            return
        }
        if (!takenPrefixes.contains(prefix)) {
            uriToPrefix[uri] = prefix
            takenPrefixes += prefix
        } else {
            // Prefix collision across different URIs — generate a unique prefix.
            var i = 2
            while (takenPrefixes.contains("${prefix}_$i")) i++
            val unique = "${prefix}_$i"
            uriToPrefix[uri] = unique
            takenPrefixes += unique
        }
    }

    private fun writeMergedValues(target: Path, docs: List<org.w3c.dom.Document>, nsSources: List<Path>) {
        val factory = DocumentBuilderFactory.newInstance()
        factory.setNamespaceAware(true)
        val doc = factory.newDocumentBuilder().newDocument()
        val root = doc.createElement("resources")
        doc.appendChild(root)

        // Declare every namespace from every source on the merged root so that
        // imported children serialize with resolvable prefixes.
        val uriToPrefix = collectNamespaces(nsSources)
        for ((uri, prefix) in uriToPrefix) {
            if (prefix.isEmpty()) {
                root.setAttributeNS(XMLNS_NS, "xmlns", uri)
            } else {
                root.setAttributeNS(XMLNS_NS, "xmlns:$prefix", uri)
            }
        }

        val seen = mutableSetOf<String>()
        for (source in docs) {
            val srcRoot = source.documentElement ?: continue
            for (child in childElements(srcRoot)) {
                val name = child.getAttribute("name")
                val localName = child.localName ?: child.tagName
                val ns = child.namespaceURI ?: ""
                val key = if (name.isNotBlank()) "$ns/$localName/$name" else null
                if (key != null) {
                    if (!seen.add(key)) continue
                }
                root.appendChild(doc.importNode(child, true))
            }
        }

        fileSystem.createDirectories(target.parent)
        val bytes = serializeDocument(doc)

        // Validate: re-parse the produced bytes through a strict parser. If this
        // throws, we never write a corrupt file to disk.
        try {
            val validator = factory.newDocumentBuilder()
            validator.parse(java.io.ByteArrayInputStream(bytes))
        } catch (e: Exception) {
            throw com.hbe.api.exception.ResourceException(
                message = "Merged values.xml failed XML validation: ${e.message}",
                suggestion = "Report this as a bug — the resource merger produced invalid XML",
                details = listOf(String(bytes, StandardCharsets.UTF_8).take(500))
            )
        }

        fileSystem.writeBytes(target, bytes)
    }

    private fun serializeDocument(doc: org.w3c.dom.Document): ByteArray {
        val transformer = javax.xml.transform.TransformerFactory.newInstance().newTransformer()
        transformer.setOutputProperty(javax.xml.transform.OutputKeys.INDENT, "yes")
        transformer.setOutputProperty(javax.xml.transform.OutputKeys.ENCODING, "utf-8")
        transformer.setOutputProperty(javax.xml.transform.OutputKeys.OMIT_XML_DECLARATION, "no")
        val out = java.io.ByteArrayOutputStream()
        transformer.transform(javax.xml.transform.dom.DOMSource(doc), javax.xml.transform.stream.StreamResult(out))
        return out.toByteArray()
    }

    private fun childElements(parent: Element): List<Element> {
        val result = mutableListOf<Element>()
        val children = parent.childNodes
        for (i in 0 until children.length) {
            val node = children.item(i)
            if (node is Element) result.add(node)
        }
        return result
    }

    internal fun parseAapt2Errors(stderr: String): List<String> {
        return stderr.lines()
            .filter { it.isNotBlank() && (it.contains("error:") || it.contains("Error:")) }
            .map { it.trim() }
    }

    private fun extractZip(zipFile: Path, outDir: Path) {
        if (Files.isDirectory(outDir)) {
            Files.walk(outDir)
                .sorted(Comparator.reverseOrder())
                .forEach { Files.deleteIfExists(it) }
        }
        Files.createDirectories(outDir)

        ZipFile(zipFile.toFile()).use { zf ->
            val entries = zf.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val entryPath = outDir.resolve(entry.name).normalize()
                if (entry.isDirectory) {
                    Files.createDirectories(entryPath)
                } else {
                    Files.createDirectories(entryPath.parent)
                    zf.getInputStream(entry).use { ins ->
                        Files.copy(ins, entryPath, StandardCopyOption.REPLACE_EXISTING)
                    }
                }
            }
        }
    }

    private fun firstElement(parent: Element, tagName: String): Element? {
        return childElements(parent, tagName).firstOrNull()
    }

    private fun childElements(parent: Element, tagName: String): List<Element> {
        val result = mutableListOf<Element>()
        val children = parent.childNodes
        for (i in 0 until children.length) {
            val node = children.item(i)
            if (node is Element && node.tagName == tagName) result.add(node)
        }
        return result
    }
}
