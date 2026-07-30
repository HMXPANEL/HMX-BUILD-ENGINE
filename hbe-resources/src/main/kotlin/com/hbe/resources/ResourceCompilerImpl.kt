package com.hbe.resources

import com.hbe.api.*
import com.hbe.api.exception.ResourceException
import java.nio.file.Path

class ResourceCompilerImpl(
    private val sdkManager: SdkManager,
    private val fileSystem: FileSystem,
    private val processRunner: ProcessRunner,
    private val logger: Logger
) : ResourceCompiler {

    override fun compile(resDir: Path, outputDir: Path): List<Path> {
        if (!fileSystem.exists(resDir)) {
            logger.warn("Resource directory not found", mapOf("dir" to resDir.toString()))
            return emptyList()
        }

        logger.info("Compiling resources", mapOf("dir" to resDir.toString()))
        return emptyList() // Will invoke aapt2 compile
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

        logger.info("Linking resources", mapOf(
            "flatFiles" to flatFiles.size.toString(),
            "compileSdk" to compileSdk.toString()
        ))

        // Placeholder — will invoke aapt2 link
        return ResourceBundle(
            resourcesArsc = outputDir.resolve("resources.arsc"),
            rJava = outputDir.resolve("gen").resolve("R.java"),
            manifest = manifest,
            configurations = setOf("default")
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
        fileSystem.copy(mainManifest.path, mergedPath)
        return mergedPath
    }
}
