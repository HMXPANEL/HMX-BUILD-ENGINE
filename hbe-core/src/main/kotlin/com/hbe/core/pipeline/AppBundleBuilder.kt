package com.hbe.core.pipeline

import com.hbe.api.*
import com.hbe.api.dto.BuildError
import com.hbe.api.dto.BuildRequest
import com.hbe.api.dto.BuildResult
import com.hbe.core.project.ModuleModel
import com.hbe.core.project.ManifestMerger
import com.hbe.core.project.ProjectModel
import com.hbe.core.project.ProjectResolver
import java.io.BufferedOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

/**
 * Builds an Android App Bundle (`.aab`) for the application module. Resources are
 * linked in proto format so the produced bundle carries `resources.pb` and
 * proto-compiled `res/`, matching the App Bundle container layout
 * (`base/manifest`, `base/dex`, `base/res`, `base/resources.pb`, `BundleConfig.pb`).
 * Library modules are compiled to classes jars and fed into the application
 * module's classpath, resources and manifest merge.
 */
class AppBundleBuilder(
    private val sdkManager: SdkManager,
    private val resourceCompiler: ResourceCompiler,
    private val sourceCompiler: SourceCompiler,
    private val dexEngine: DexEngine,
    private val packager: Packager,
    private val toolRunner: ToolRunner,
    private val fileSystem: FileSystem,
    private val logger: Logger,
    private val resolver: ProjectResolver
) {

    fun build(model: ProjectModel, request: BuildRequest, config: EngineConfig): BuildResult {
        val appModule = model.applicationModule ?: return BuildResult(
            status = BuildResult.Status.FAILURE,
            error = BuildError(
                phase = "SETUP", code = "NO_APP_MODULE",
                message = "No Android application module found",
                suggestion = "Ensure a module applies the com.android.application plugin"
            ),
            buildId = "bld-aab"
        )

        val buildId = "bld-aab-" + UUID.randomUUID().toString().substring(0, 8)
        val start = System.currentTimeMillis()
        logger.info("App Bundle build", mapOf("order" to model.moduleOrder.joinToString(",") { it.path }))

        val compileSdk = appModule.compileSdk ?: 34
        val androidJar = sdkManager.resolveSdk(compileSdk).androidJar

        val libJars = mutableListOf<Path>()
        val libRes = mutableListOf<Path>()
        val libManifests = mutableListOf<Path>()
        for (lib in model.moduleOrder) {
            if (lib.path == appModule.path || !lib.isAndroidModule) continue
            val built = buildLibrary(lib, model, compileSdk)
            libJars.add(built.classesJar)
            built.resDir?.let { libRes.add(it) }
            built.manifest?.let { libManifests.add(it) }
        }

        val appDeps = resolver.resolve(model, appModule.path)
        val appRequest = request.copy(projectDir = appModule.dir.toString())
        val projectRoot = Path.of(appRequest.projectDir)
        println("DIAG projectRoot=$projectRoot appModule.dir=${appModule.dir} appModule.path=${appModule.path} moduleOrder=${model.moduleOrder.map { it.path }}")
        val manifest = findManifest(projectRoot)
        println("DIAG manifest=$manifest exists=${manifest?.let { fileSystem.exists(it) }}")
        if (manifest == null) {
            return BuildResult(status = BuildResult.Status.FAILURE,
                error = BuildError(phase = "SETUP", code = "NO_MANIFEST", message = "No AndroidManifest.xml found"),
                buildId = buildId)
        }
        val resDir = findResDir(projectRoot)
        val applicationId = appModule.applicationId ?: appModule.namespace ?: projectRoot.fileName.toString()

        val buildRoot = projectRoot.resolve("build/hbe")
        fileSystem.createDirectories(buildRoot)

        val mergedManifest = if (libManifests.isNotEmpty() || appDeps.libraryManifests.isNotEmpty()) {
            ManifestMerger(fileSystem, logger).merge(manifest, appDeps.libraryManifests + libManifests, applicationId)
        } else manifest

        val mergedRes = buildRoot.resolve("res/merged")
        val flatOut = buildRoot.resolve("res/flat")
        val linkOut = buildRoot.resolve("res/link")
        val allLibRes = appDeps.libraryResDirs + libRes
        resourceCompiler.mergeResources(mergedRes, resDir ?: mergedRes, allLibRes)
        val flats = resourceCompiler.compile(mergedRes, flatOut)
        val bundle = resourceCompiler.linkProto(flats, mergedManifest, linkOut, compileSdk)
        println("DIAG linkProto rJava=${bundle.rJava} resourcesPb=${bundle.resourcesPb} manifest=${bundle.manifest} resDirs=${bundle.compiledResDirectories}")

        val classpath = Classpath(listOf(androidJar) + appDeps.classpath + libJars)
        val sources = mutableListOf<Path>()
        appModule.kotlinSourceDirs.forEach { sources.addAll(fileSystem.walkFiles(it, "*.kt")) }
        appModule.javaSourceDirs.forEach { sources.addAll(fileSystem.walkFiles(it, "*.java")) }
        sources.add(bundle.rJava)

        val classesDir = buildRoot.resolve("classes")
        fileSystem.createDirectories(classesDir)
        if (sources.any { it.toString().endsWith(".kt") }) {
            sourceCompiler.compileKotlin(sources.toSet(), classpath, classesDir, useCompose = appModule.buildFeatures["compose"] == true)
        }
        if (sources.any { it.toString().endsWith(".java") }) {
            sourceCompiler.compileJava(sources.toSet(), classpath, classesDir)
        }

        val dexOut = buildRoot.resolve("dex")
        val dexOutput = dexEngine.dex(classesDir.walkFilesSafe(), DexConfig(
            minSdk = appModule.minSdk ?: 24,
            debug = request.variant != "release",
            outputDir = dexOut,
            libraryJars = listOf(androidJar),
            isRelease = request.variant == "release"
        ))
        println("DIAG dexFiles=${dexOutput.dexFiles} classesDirHasClasses=${classesDir.walkFilesSafe()}")
        val dexFile = dexOutput.dexFiles.firstOrNull()
            ?: return BuildResult(status = BuildResult.Status.FAILURE,
                error = BuildError(phase = "DEX", code = "NO_DEX", message = "No dex file produced"),
                buildId = buildId)

        val baseDir = buildRoot.resolve("base-module")
        fileSystem.createDirectories(baseDir.resolve("manifest"))
        fileSystem.createDirectories(baseDir.resolve("dex"))
        fileSystem.copy(bundle.manifest, baseDir.resolve("manifest/AndroidManifest.xml"))
        fileSystem.copy(dexFile, baseDir.resolve("dex/classes.dex"))
        bundle.resourcesPb?.let { fileSystem.copy(it, baseDir.resolve("resources.pb")) }
        for (resDirOut in bundle.compiledResDirectories) {
            copyDir(resDirOut, baseDir.resolve("res"))
        }

        val aab = packager.packageAab(baseDir, buildRoot.resolve("app.aab"))

        val duration = System.currentTimeMillis() - start
        logger.info("App Bundle built", mapOf("path" to aab.toString(), "size" to fileSystem.size(aab).toString()))
        return BuildResult(
            status = BuildResult.Status.SUCCESS,
            aabPath = aab.toString(),
            apkSizeBytes = fileSystem.size(aab),
            totalDurationMs = duration,
            buildId = buildId
        )
    }

    private data class BuiltLibrary(val classesJar: Path, val resDir: Path?, val manifest: Path?)

    private fun buildLibrary(lib: ModuleModel, model: ProjectModel, compileSdk: Int): BuiltLibrary {
        val androidJar = sdkManager.resolveSdk(compileSdk).androidJar
        val libDeps = resolver.resolve(model, lib.path)
        val baseClasspath = listOf(androidJar) + libDeps.classpath

        val buildRoot = lib.dir.resolve("build/hbe")
        fileSystem.createDirectories(buildRoot)
        val classesDir = buildRoot.resolve("classes")
        fileSystem.createDirectories(classesDir)

        val sources = mutableListOf<Path>()
        lib.kotlinSourceDirs.forEach { sources.addAll(fileSystem.walkFiles(it, "*.kt")) }
        lib.javaSourceDirs.forEach { sources.addAll(fileSystem.walkFiles(it, "*.java")) }

        val classpath = Classpath(baseClasspath)
        if (sources.any { it.toString().endsWith(".kt") }) {
            sourceCompiler.compileKotlin(sources.toSet(), classpath, classesDir, useCompose = lib.buildFeatures["compose"] == true)
        }
        if (sources.any { it.toString().endsWith(".java") }) {
            sourceCompiler.compileJava(sources.toSet(), classpath, classesDir)
        }

        val classesJar = buildRoot.resolve("classes.jar")
        jarDirectory(classesDir, classesJar)
        return BuiltLibrary(classesJar, lib.resDir, lib.manifest)
    }

    private fun Path.walkFilesSafe(): Set<Path> {
        if (!Files.isDirectory(this)) return emptySet()
        return Files.walk(this)
            .filter { Files.isRegularFile(it) && it.toString().endsWith(".class") }
            .collect(java.util.stream.Collectors.toSet())
    }

    private fun copyDir(src: Path, dst: Path) {
        if (!Files.isDirectory(src)) return
        Files.walk(src).filter { Files.isRegularFile(it) }.forEach { file ->
            val target = dst.resolve(src.relativize(file).toString().replace('\\', '/'))
            fileSystem.createDirectories(target.parent)
            fileSystem.copy(file, target)
        }
    }

    private fun findManifest(root: Path): Path? = listOf(
        root.resolve("AndroidManifest.xml"),
        root.resolve("src/main/AndroidManifest.xml")
    ).firstOrNull { fileSystem.exists(it) }

    private fun findResDir(root: Path): Path? = listOf(
        root.resolve("res"),
        root.resolve("src/main/res")
    ).firstOrNull { Files.isDirectory(it) }

    private fun jarDirectory(dir: Path, jarPath: Path) {
        if (Files.exists(jarPath)) Files.delete(jarPath)
        JarOutputStream(BufferedOutputStream(Files.newOutputStream(jarPath))).use { jos ->
            Files.walk(dir)
                .filter { Files.isRegularFile(it) && it.toString().endsWith(".class") }
                .forEach { f ->
                    jos.putNextEntry(JarEntry(dir.relativize(f).toString().replace('\\', '/')))
                    Files.copy(f, jos)
                    jos.closeEntry()
                }
        }
    }
}
