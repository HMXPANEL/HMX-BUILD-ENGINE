package com.hbe.resources

import com.hbe.api.*
import com.hbe.api.exception.ResourceException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

class ResourceCompilerImpl(
    private val sdkManager: SdkManager,
    private val fileSystem: FileSystem,
    private val toolRunner: ToolRunner,
    private val logger: Logger
) : ResourceCompiler {

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

        ZipInputStream(Files.newInputStream(zipFile)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val entryPath = outDir.resolve(entry.name).normalize()
                if (entry.isDirectory) {
                    Files.createDirectories(entryPath)
                } else {
                    Files.createDirectories(entryPath.parent)
                    Files.copy(zis, entryPath, StandardCopyOption.REPLACE_EXISTING)
                }
                zis.closeEntry()
                entry = zis.nextEntry
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
