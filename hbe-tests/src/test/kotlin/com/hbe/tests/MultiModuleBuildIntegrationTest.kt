package com.hbe.tests

import com.hbe.api.*
import com.hbe.api.MemoryMonitor
import com.hbe.api.dto.BuildRequest
import com.hbe.api.dto.BuildResult
import com.hbe.cache.CacheManagerImpl
import com.hbe.core.BuildContextImpl
import com.hbe.core.DefaultLogger
import com.hbe.core.event.InMemoryBuildEventBus
import com.hbe.core.event.EventEmittingToolRunner
import com.hbe.core.pipeline.MultiModuleBuilder
import com.hbe.core.project.ProjectImporter
import com.hbe.dependency.DependencyManagerImpl
import com.hbe.infra.JavaNetHttpClient
import com.hbe.infra.OsFileSystem
import com.hbe.infra.OsProcessRunner
import com.hbe.resources.ResourceCompilerImpl
import com.hbe.compiler.SourceCompilerImpl
import com.hbe.dex.DexEngineImpl
import com.hbe.packager.PackagerImpl
import com.hbe.signer.SignerImpl
import com.hbe.sdk.SdkManagerImpl
import com.hbe.sdk.ToolRunnerImpl
import com.hbe.scheduler.TaskScheduler
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * End-to-end build of a multi-module project (`:app` depends on `:core`) with
 * real Android tooling: import, topological ordering and a signed APK that
 * contains both the application and library classes.
 */
class MultiModuleBuildIntegrationTest {

    @TempDir
    lateinit var tempDir: Path

    private val logger = DefaultLogger()
    private val fileSystem = OsFileSystem()

    @Test
    fun `multi-module project imports, orders and builds to a signed APK`() {
        val sdkManager = SdkManagerImpl(fileSystem, OsProcessRunner(), logger)
        assumeTrue(sdkManager.listInstalledSdk().sdkRoot != null, "Android SDK required")
        assumeTrue(sdkManager.findTool("aapt2") != null, "aapt2 required")

        val projectDir = copyProject()
        val importer = ProjectImporter(fileSystem, logger)
        val model = importer.importProject(projectDir)

        assertEquals(2, model.modules.size, "expected :app and :core modules")
        assertEquals(listOf(":core", ":app"), model.moduleOrder.map { it.path }, "libraries must build before the application")
        val app = model.applicationModule ?: error("no application module")
        assertEquals(listOf(":core"), app.projectDependencies, "app must depend on :core")

        val dependencyManager = DependencyManagerImpl(
            fileSystem, JavaNetHttpClient(), CacheManagerImpl(fileSystem, tempDir.resolve("dep-cache")), logger
        )
        val resolver = com.hbe.core.project.ProjectResolver(dependencyManager, logger)
        val toolRunner = EventEmittingToolRunner(ToolRunnerImpl(sdkManager, OsProcessRunner(), logger), InMemoryBuildEventBus())

        val builder = MultiModuleBuilder(
            sdkManager = sdkManager,
            resourceCompiler = ResourceCompilerImpl(sdkManager, fileSystem, toolRunner, logger),
            sourceCompiler = SourceCompilerImpl(sdkManager, fileSystem, toolRunner, logger),
            dexEngine = DexEngineImpl(sdkManager, fileSystem, toolRunner, logger),
            packager = PackagerImpl(fileSystem, toolRunner, logger),
            signer = SignerImpl(fileSystem, toolRunner, logger),
            toolRunner = toolRunner,
            fileSystem = fileSystem,
            logger = logger,
            cacheManager = CacheManagerImpl(fileSystem, tempDir.resolve("cache")),
            scheduler = TaskScheduler(NoopMemoryMonitor2(), logger),
            memoryMonitor = NoopMemoryMonitor2(),
            resolver = resolver
        )

        val result = builder.build(model, BuildRequest(projectDir = projectDir.toString()), EngineConfig())
        assertEquals(BuildResult.Status.SUCCESS, result.status, "error: ${result.error?.message}")

        val apk = projectDir.resolve("app/build/hbe/signed/app.apk")
        assertTrue(Files.exists(apk), "signed APK not produced at $apk")
    }

    private fun copyProject(): Path {
        val source = Path.of("projects/TEST-10-multimodule")
        val target = tempDir.resolve("TEST-10-multimodule")
        Files.walk(source).forEach { p ->
            val rel = source.relativize(p)
            val dest = target.resolve(rel.toString())
            if (Files.isDirectory(p)) Files.createDirectories(dest) else Files.copy(p, dest)
        }
        return target
    }

    private class NoopMemoryMonitor2 : MemoryMonitor {
        override fun getAvailableMemoryBytes(): Long = 1024L * 1024 * 1024
        override fun getTotalMemoryBytes(): Long = 2048L * 1024 * 1024
        override fun getUsedMemoryBytes(): Long = 1024L * 1024 * 1024
        override fun isLowMemory(): Boolean = false
        override fun getPressure(): MemoryMonitor.MemoryPressure = MemoryMonitor.MemoryPressure.NONE
        override fun releaseMemory() {}
        override fun registerPhase(phase: com.hbe.api.Phase) {}
    }
}
