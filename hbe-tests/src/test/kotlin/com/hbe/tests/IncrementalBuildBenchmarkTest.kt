package com.hbe.tests

import com.hbe.api.*
import com.hbe.api.dto.BuildRequest
import com.hbe.api.dto.BuildResult
import com.hbe.cache.CacheManagerImpl
import com.hbe.core.BuildContextImpl
import com.hbe.core.DefaultLogger
import com.hbe.core.event.EventEmittingToolRunner
import com.hbe.core.event.InMemoryBuildEventBus
import com.hbe.core.pipeline.IncrementalBuildPipeline
import com.hbe.infra.OsFileSystem
import com.hbe.infra.OsProcessRunner
import com.hbe.scheduler.TaskScheduler
import com.hbe.sdk.SdkManagerImpl
import com.hbe.sdk.ToolRunnerImpl
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Benchmarks the incremental build engine end to end with real Android tooling:
 *   build 1 — clean, cold cache        (all phases run)
 *   build 2 — no changes               (all phases cache hits, APK byte-identical)
 *   build 3 — one source file changed  (only downstream phases re-run)
 *   build 4 — build dir deleted        (artifacts restored from cache, APK byte-identical)
 * and writes a report with graph, cache stats, timings and a performance comparison.
 */
class IncrementalBuildBenchmarkTest {

    @TempDir
    lateinit var tempDir: Path

    private val logger = DefaultLogger()
    private val fileSystem = OsFileSystem()

    @Test
    fun `incremental builds reuse artifacts and report cache statistics`() {
        val sdkManager = SdkManagerImpl(fileSystem, OsProcessRunner(), logger)
        assumeTrue(sdkManager.listInstalledSdk().sdkRoot != null, "Android SDK required")
        assumeTrue(sdkManager.findTool("aapt2") != null, "aapt2 required")

        val projectDir = createProject()
        val buildRoot = projectDir.resolve("build/hbe")
        val eventBus = InMemoryBuildEventBus()
        val countingRunner = CountingToolRunner(ToolRunnerImpl(sdkManager, OsProcessRunner(), logger))
        val toolRunner = EventEmittingToolRunner(countingRunner, eventBus)

        val pipeline = IncrementalBuildPipeline(
            sdkManager = sdkManager,
            resourceCompiler = com.hbe.resources.ResourceCompilerImpl(sdkManager, fileSystem, toolRunner, logger),
            sourceCompiler = com.hbe.compiler.SourceCompilerImpl(sdkManager, fileSystem, toolRunner, logger),
            dexEngine = com.hbe.dex.DexEngineImpl(sdkManager, fileSystem, toolRunner, logger),
            packager = com.hbe.packager.PackagerImpl(fileSystem, toolRunner, logger),
            signer = com.hbe.signer.SignerImpl(fileSystem, toolRunner, logger),
            toolRunner = toolRunner,
            fileSystem = fileSystem,
            eventBus = eventBus,
            logger = logger,
            cacheManager = CacheManagerImpl(fileSystem, tempDir.resolve("cache")),
            scheduler = TaskScheduler(NoopMemoryMonitor(), logger),
            memoryMonitor = NoopMemoryMonitor()
        )

        val context = { id: String -> BuildContextImpl(id, BuildRequest(projectDir = projectDir.toString()), com.hbe.api.EngineConfig()) }

        // Build 1 — clean, cold cache
        val clean = pipeline.execute(context("bld-clean"))
        assertEquals(BuildResult.Status.SUCCESS, clean.status, "error: ${clean.error?.message}")
        assertEquals(0, clean.cacheHits)
        assertEquals(7, clean.cacheMisses)
        val cleanTools = countingRunner.calls.toList()
        assertTrue(cleanTools.contains("aapt2"), "aapt2 should run on a clean build")
        assertTrue(cleanTools.contains("d8"), "d8 should run on a clean build")
        val cleanApk = Files.readAllBytes(buildRoot.resolve("signed/app.apk"))

        // Build 2 — nothing changed: every phase is a cache hit
        val warm = pipeline.execute(context("bld-warm"))
        assertEquals(BuildResult.Status.SUCCESS, warm.status)
        assertEquals(7, warm.cacheHits)
        assertEquals(0, warm.cacheMisses)
        val warmTools = countingRunner.calls.subList(cleanTools.size, countingRunner.calls.size)
        assertTrue(warmTools.isEmpty(), "no tool should run on a fully cached build, got: $warmTools")
        val warmApk = Files.readAllBytes(buildRoot.resolve("signed/app.apk"))
        assertArrayEquals(cleanApk, warmApk, "warm build must reproduce the identical APK")

        // Build 3 — one source file changed: only downstream phases re-run
        editSource(projectDir)
        val changed = pipeline.execute(context("bld-changed"))
        assertEquals(BuildResult.Status.SUCCESS, changed.status, "error: ${changed.error?.message}")
        assertEquals(2, changed.cacheHits)  // RESOURCE_COMPILE, RESOURCE_LINK
        assertEquals(5, changed.cacheMisses)
        val changedTools = countingRunner.calls.subList(cleanTools.size + warmTools.size, countingRunner.calls.size)
        assertTrue("aapt2" !in changedTools, "resources unchanged, aapt2 must not run: $changedTools")
        assertTrue("d8" in changedTools, "changed classes must re-run d8: $changedTools")
        assertTrue("zipalign" in changedTools, "changed classes must re-package/re-align: $changedTools")
        assertTrue("apksigner" in changedTools, "changed classes must re-sign: $changedTools")
        assertEquals(3, changedTools.size, "expected d8 + zipalign + apksigner, got: $changedTools")

        // Build 4 — build dir deleted, cache warm: artifacts restored, APK byte-identical
        Files.walk(buildRoot).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        val restored = pipeline.execute(context("bld-restored"))
        assertEquals(BuildResult.Status.SUCCESS, restored.status, "error: ${restored.error?.message}")
        assertEquals(7, restored.cacheHits)
        assertEquals(0, restored.cacheMisses)
        val restoredTools = countingRunner.calls.subList(cleanTools.size + warmTools.size + changedTools.size, countingRunner.calls.size)
        assertTrue(restoredTools.isEmpty(), "cache restore must not run tools: $restoredTools")
        assertArrayEquals(warmApk, Files.readAllBytes(buildRoot.resolve("signed/app.apk")),
            "restored build must reproduce the identical APK")

        // Report contains graph, cache statistics, timings and a performance comparison
        val report = String(Files.readAllBytes(buildRoot.resolve("incremental-report.txt")))
        assertTrue(report.contains("TASK GRAPH"))
        assertTrue(report.contains("CACHE STATISTICS"))
        assertTrue(report.contains("INCREMENTAL TIMINGS"))
        assertTrue(report.contains("PERFORMANCE COMPARISON"))
        assertTrue(report.contains("JAVA_COMPILE"))

        // Print the benchmark summary
        println("""
            |
            |==== INCREMENTAL BUILD BENCHMARK ====
            |Clean build:        ${clean.totalDurationMs}ms (${clean.cacheMisses} misses)
            |Warm build:         ${warm.totalDurationMs}ms (${warm.cacheHits} hits)
            |Single-change:      ${changed.totalDurationMs}ms (${changed.cacheHits} hits, ${changed.cacheMisses} misses)
            |Cache-restore:      ${restored.totalDurationMs}ms (${restored.cacheHits} hits)
            |Warm speedup:       ${"%.1f".format(clean.totalDurationMs.toDouble() / warm.totalDurationMs.coerceAtLeast(1))}x
            |Report:             $buildRoot/incremental-report.txt
            |======================================
        """.trimMargin())
    }

    private fun editSource(projectDir: Path) {
        val src = projectDir.resolve("src/main/java/com/hbe/testapp/MainActivity.java")
        val original = Files.readString(src)
        val idx = original.lastIndexOf('}')
        Files.writeString(src, original.substring(0, idx) + "\n    public void onResume() {}\n" + original.substring(idx))
    }

    private fun createProject(): Path {
        val projectDir = tempDir.resolve("benchapp")
        val srcDir = projectDir.resolve("src/main/java/com/hbe/testapp")
        Files.createDirectories(srcDir)
        Files.createDirectories(projectDir.resolve("res/values"))
        Files.createDirectories(projectDir.resolve("res/layout"))

        Files.writeString(projectDir.resolve("AndroidManifest.xml"), """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                package="com.hbe.testapp">
                <uses-sdk android:minSdkVersion="24" android:targetSdkVersion="34"/>
                <application android:label="@string/app_name">
                    <activity android:name=".MainActivity" android:exported="true">
                        <intent-filter>
                            <action android:name="android.intent.action.MAIN"/>
                            <category android:name="android.intent.category.LAUNCHER"/>
                        </intent-filter>
                    </activity>
                </application>
            </manifest>
        """.trimIndent())

        Files.writeString(projectDir.resolve("res/values/strings.xml"), """
            <?xml version="1.0" encoding="utf-8"?>
            <resources>
                <string name="app_name">Bench App</string>
            </resources>
        """.trimIndent())

        Files.writeString(projectDir.resolve("res/layout/activity_main.xml"), """
            <?xml version="1.0" encoding="utf-8"?>
            <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
                android:layout_width="match_parent"
                android:layout_height="match_parent">
                <TextView
                    android:id="@+id/text"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:text="@string/app_name" />
            </LinearLayout>
        """.trimIndent())

        Files.writeString(srcDir.resolve("MainActivity.java"), """
            package com.hbe.testapp;

            import android.app.Activity;
            import android.os.Bundle;

            public class MainActivity extends Activity {
                @Override
                protected void onCreate(Bundle savedInstanceState) {
                    super.onCreate(savedInstanceState);
                    setContentView(R.layout.activity_main);
                }
            }
        """.trimIndent())

        return projectDir
    }

    private class CountingToolRunner(private val delegate: ToolRunner) : ToolRunner {
        val calls = mutableListOf<String>()
        override fun run(tool: String, args: List<String>, options: ToolOptions): ToolResult {
            calls.add(tool)
            return delegate.run(tool, args, options)
        }
    }

    private class NoopMemoryMonitor : MemoryMonitor {
        override fun getAvailableMemoryBytes(): Long = 1024L * 1024 * 1024
        override fun getTotalMemoryBytes(): Long = 2048L * 1024 * 1024
        override fun getUsedMemoryBytes(): Long = 1024L * 1024 * 1024
        override fun isLowMemory(): Boolean = false
        override fun getPressure(): MemoryMonitor.MemoryPressure = MemoryMonitor.MemoryPressure.NONE
        override fun releaseMemory() {}
        override fun registerPhase(phase: Phase) {}
    }
}
