package com.hbe.core.pipeline

import com.hbe.api.*
import com.hbe.api.dto.BuildError
import com.hbe.api.dto.BuildRequest
import com.hbe.api.dto.BuildResult
import com.hbe.core.BuildContextImpl
import com.hbe.core.event.InMemoryBuildEventBus
import com.hbe.core.project.ModuleModel
import com.hbe.core.project.ProjectModel
import com.hbe.core.project.ProjectResolver
import com.hbe.scheduler.TaskScheduler
import java.io.BufferedOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

/**
 * Builds a multi-module Gradle project by compiling each library module into a
 * classes jar (plus its resources and manifest) and feeding those artifacts into
 * the application module's [IncrementalBuildPipeline]. Libraries are built in
 * dependency order (see [ProjectModel.moduleOrder]); the application module is
 * always built last so every dependency is available on its classpath.
 */
class MultiModuleBuilder(
    private val sdkManager: SdkManager,
    private val resourceCompiler: ResourceCompiler,
    private val sourceCompiler: SourceCompiler,
    private val dexEngine: DexEngine,
    private val packager: Packager,
    private val signer: Signer,
    private val toolRunner: ToolRunner,
    private val fileSystem: FileSystem,
    private val logger: Logger,
    private val cacheManager: CacheManager,
    private val scheduler: TaskScheduler,
    private val memoryMonitor: MemoryMonitor,
    private val resolver: ProjectResolver
) {

    fun build(model: ProjectModel, request: BuildRequest, config: EngineConfig): BuildResult {
        val appModule = model.applicationModule
            ?: return BuildResult(
                status = BuildResult.Status.FAILURE,
                error = BuildError(
                    phase = "SETUP", code = "NO_APP_MODULE",
                    message = "No Android application module found",
                    suggestion = "Ensure a module applies the com.android.application plugin"
                ),
                buildId = "bld-multi"
            )

        val buildId = "bld-" + UUID.randomUUID().toString().substring(0, 8)
        logger.info("Multi-module build", mapOf("order" to model.moduleOrder.joinToString(",") { it.path }))

        val appDeps = resolver.resolve(model, appModule.path)
        val built = mutableListOf<BuiltLibrary>()

        for (lib in model.moduleOrder) {
            if (lib.path == appModule.path || !lib.isAndroidModule) continue
            built += buildLibrary(lib, model)
        }

        val combined = combine(appDeps, built)

        val pipeline = IncrementalBuildPipeline(
            sdkManager = sdkManager,
            resourceCompiler = resourceCompiler,
            sourceCompiler = sourceCompiler,
            dexEngine = dexEngine,
            packager = packager,
            signer = signer,
            toolRunner = toolRunner,
            fileSystem = fileSystem,
            eventBus = InMemoryBuildEventBus(),
            logger = logger,
            cacheManager = cacheManager,
            scheduler = scheduler,
            memoryMonitor = memoryMonitor,
            projectDependencies = combined
        )

        val appRequest = request.copy(projectDir = appModule.dir.toString())
        return pipeline.execute(BuildContextImpl(buildId, appRequest, config))
    }

    private data class BuiltLibrary(
        val classesJar: Path,
        val resDir: Path?,
        val manifest: Path?,
        val mavenClasspath: List<Path>
    )

    private fun buildLibrary(lib: ModuleModel, model: ProjectModel): BuiltLibrary {
        val compileSdk = lib.compileSdk ?: 34
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

        val resDir = lib.resDir
        val manifest = lib.manifest
        var rJava: Path? = null
        if (resDir != null && fileSystem.exists(resDir) && manifest != null) {
            val mergedRes = buildRoot.resolve("res/merged")
            val flatOut = buildRoot.resolve("res/flat")
            val linkOut = buildRoot.resolve("res/link")
            resourceCompiler.mergeResources(mergedRes, resDir, libDeps.libraryResDirs)
            val flats = resourceCompiler.compile(mergedRes, flatOut)
            rJava = resourceCompiler.link(flats, manifest, linkOut, compileSdk).rJava
        }
        if (rJava != null) sources.add(rJava)

        val classpath = Classpath(baseClasspath)
        if (sources.any { it.toString().endsWith(".kt") }) {
            sourceCompiler.compileKotlin(sources.toSet(), classpath, classesDir, useCompose = lib.buildFeatures["compose"] == true)
        }
        if (sources.any { it.toString().endsWith(".java") }) {
            sourceCompiler.compileJava(sources.toSet(), classpath, classesDir)
        }

        val classesJar = buildRoot.resolve("classes.jar")
        jarDirectory(classesDir, classesJar)

        return BuiltLibrary(classesJar, resDir, manifest, libDeps.classpath)
    }

    private fun combine(appDeps: ProjectDependencies, libs: List<BuiltLibrary>): ProjectDependencies {
        val libJars = libs.mapNotNull { it.classesJar }
        val libRes = libs.mapNotNull { it.resDir }
        val libManifests = libs.mapNotNull { it.manifest }
        val libMaven = libs.flatMap { it.mavenClasspath }
        return appDeps.copy(
            classpath = (appDeps.classpath + libJars + libMaven).distinct().sorted(),
            libraryResDirs = (appDeps.libraryResDirs + libRes).distinct().sorted(),
            libraryManifests = (appDeps.libraryManifests + libManifests).distinct().sorted()
        )
    }

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
