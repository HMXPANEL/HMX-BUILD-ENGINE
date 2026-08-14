package com.hbe.tests

import com.hbe.api.*
import com.hbe.api.dto.BuildRequest
import com.hbe.api.dto.BuildResult
import com.hbe.cache.CacheManagerImpl
import com.hbe.core.DefaultLogger
import com.hbe.core.event.EventEmittingToolRunner
import com.hbe.core.event.InMemoryBuildEventBus
import com.hbe.core.pipeline.AppBundleBuilder
import com.hbe.core.project.ProjectImporter
import com.hbe.dependency.DependencyManagerImpl
import com.hbe.infra.JavaNetHttpClient
import com.hbe.infra.OsFileSystem
import com.hbe.infra.OsProcessRunner
import com.hbe.packager.PackagerImpl
import com.hbe.resources.ResourceCompilerImpl
import com.hbe.compiler.SourceCompilerImpl
import com.hbe.dex.DexEngineImpl
import com.hbe.sdk.SdkManagerImpl
import com.hbe.sdk.ToolRunnerImpl
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * End-to-end App Bundle (`.aab`) build with real Android tooling: import the
 * project, link resources in proto format, assemble the `base` module and write
 * a valid bundle archive.
 */
class AppBundleIntegrationTest {

    @TempDir
    lateinit var tempDir: Path

    private val logger = DefaultLogger()
    private val fileSystem = OsFileSystem()

    @Test
    fun `builds an android app bundle to a valid aab`() {
        val sdkManager = SdkManagerImpl(fileSystem, OsProcessRunner(), logger)
        assumeTrue(sdkManager.listInstalledSdk().sdkRoot != null, "Android SDK required")
        assumeTrue(sdkManager.findTool("aapt2") != null, "aapt2 required")

        val projectDir = copyProject()
        val importer = ProjectImporter(fileSystem, logger)
        val model = importer.importProject(projectDir)
        assertNotNull(model.applicationModule, "no application module detected")

        val dependencyManager = DependencyManagerImpl(
            fileSystem, JavaNetHttpClient(), CacheManagerImpl(fileSystem, tempDir.resolve("dep")), logger
        )
        val resolver = com.hbe.core.project.ProjectResolver(dependencyManager, logger)
        val toolRunner = EventEmittingToolRunner(ToolRunnerImpl(sdkManager, OsProcessRunner(), logger), InMemoryBuildEventBus())

        val builder = AppBundleBuilder(
            sdkManager = sdkManager,
            resourceCompiler = ResourceCompilerImpl(sdkManager, fileSystem, toolRunner, logger),
            sourceCompiler = SourceCompilerImpl(sdkManager, fileSystem, toolRunner, logger),
            dexEngine = DexEngineImpl(sdkManager, fileSystem, toolRunner, logger),
            packager = PackagerImpl(fileSystem, toolRunner, logger),
            toolRunner = toolRunner,
            fileSystem = fileSystem,
            logger = logger,
            resolver = resolver
        )

        val result = builder.build(model, BuildRequest(projectDir = projectDir.toString(), format = "aab"), EngineConfig())
        val aab = projectDir.resolve("build/hbe/app.aab")
        result.error?.let { err ->
            println("AAB BUILD ERROR phase=${err.phase} code=${err.code} message=${err.message}")
            err.details.let { println("details=$it") }
        }
        println("AAB STATUS=${result.status} aabExists=${Files.exists(aab)}")
        try {
            Files.walk(projectDir.resolve("build/hbe")).forEach { p -> println("  fs: $p") }
        } catch (_: Exception) { }
        assertEquals(BuildResult.Status.SUCCESS, result.status, "error: ${result.error?.message}")

        val aab = projectDir.resolve("build/hbe/app.aab")
        assertTrue(Files.exists(aab), "aab not produced at $aab")

        val entries = mutableSetOf<String>()
        ZipFile(aab.toFile()).use { zf -> zf.entries().toList().forEach { entries.add(it.name) } }
        assertTrue(entries.contains("BundleConfig.pb"), "missing BundleConfig.pb: $entries")
        assertTrue(entries.any { it == "base/manifest/AndroidManifest.xml" }, "missing base manifest: $entries")
        assertTrue(entries.any { it.startsWith("base/dex/") }, "missing base dex: $entries")
        assertTrue(entries.any { it == "base/resources.pb" }, "missing resources.pb: $entries")
    }

    private fun copyProject(): Path {
        val source = Path.of("projects/TEST-11-appbundle")
        val target = tempDir.resolve("TEST-11-appbundle")
        Files.walk(source).forEach { p ->
            val rel = source.relativize(p)
            val dest = target.resolve(rel.toString())
            if (Files.isDirectory(p)) Files.createDirectories(dest) else Files.copy(p, dest)
        }
        return target
    }
}
