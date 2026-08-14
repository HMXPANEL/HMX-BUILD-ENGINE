package com.hbe.core.pipeline

import com.hbe.api.*
import com.hbe.api.dto.BuildRequest
import com.hbe.api.dto.BuildResult
import com.hbe.api.dto.PhaseTiming
import com.hbe.api.event.*
import com.hbe.cache.CacheManagerImpl
import com.hbe.core.BuildContextImpl
import com.hbe.core.DefaultLogger
import com.hbe.core.PhaseExecutor
import com.hbe.core.event.EventEmittingToolRunner
import com.hbe.core.event.InMemoryBuildEventBus
import com.hbe.infra.OsFileSystem
import com.hbe.scheduler.TaskScheduler
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class IncrementalBuildPipelineTest {

    @TempDir
    lateinit var tempDir: Path

    private val logger = DefaultLogger()
    private val sdkManager = FakeSdkManager()
    private val resourceCompiler = FakeResourceCompiler()
    private val sourceCompiler = FakeSourceCompiler()
    private val dexEngine = FakeDexEngine()
    private val packager = FakePackager()
    private val signer = FakeSigner()

    private lateinit var projectDir: Path
    private lateinit var buildRoot: Path
    private lateinit var events: MutableList<BuildEvent>
    private lateinit var eventBus: InMemoryBuildEventBus

    private fun setup() {
        projectDir = tempDir.resolve("testapp")
        buildRoot = projectDir.resolve("build/hbe")
        val srcDir = projectDir.resolve("src/main/java/com/hbe/testapp")
        Files.createDirectories(srcDir)
        Files.createDirectories(projectDir.resolve("res/values"))
        writeManifest()
        writeMainActivity()
        Files.write(projectDir.resolve("res/values/strings.xml"), "<resources></resources>".toByteArray())

        resourceCompiler.resetCounts()
        sourceCompiler.resetCounts()
        dexEngine.resetCounts()
        packager.resetCounts()
        signer.resetCounts()

        eventBus = InMemoryBuildEventBus()
        events = mutableListOf()
        eventBus.subscribe { events.add(it) }
    }

    private fun writeMainActivity(content: String = """
        package com.hbe.testapp;
        public class MainActivity { public void onCreate() {} }
    """.trimIndent()) {
        Files.write(projectDir.resolve("src/main/java/com/hbe/testapp/MainActivity.java"), content.toByteArray())
    }

    private fun writeManifest() {
        Files.write(projectDir.resolve("AndroidManifest.xml"), """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                package="com.hbe.testapp">
                <uses-sdk android:minSdkVersion="24" android:targetSdkVersion="34"/>
                <application android:label="Test"/>
            </manifest>
        """.trimIndent().toByteArray())
    }

    private fun buildPipeline(): IncrementalBuildPipeline {
        return IncrementalBuildPipeline(
            sdkManager = sdkManager,
            resourceCompiler = resourceCompiler,
            sourceCompiler = sourceCompiler,
            dexEngine = dexEngine,
            packager = packager,
            signer = signer,
            toolRunner = EventEmittingToolRunner(NoopToolRunner, eventBus),
            fileSystem = OsFileSystem(),
            eventBus = eventBus,
            logger = logger,
            cacheManager = CacheManagerImpl(OsFileSystem(), tempDir.resolve("cache")),
            scheduler = TaskScheduler(NoopMemoryMonitor(), logger),
            memoryMonitor = NoopMemoryMonitor()
        )
    }

    private fun runBuild(pipeline: IncrementalBuildPipeline, buildId: String): BuildResult {
        val context = BuildContextImpl(
            buildId = buildId,
            request = BuildRequest(projectDir = projectDir.toString(), compileSdk = 35),
            config = EngineConfig(autoDownloadSdk = false)
        )
        return pipeline.execute(context)
    }

    private fun toolPhases(result: BuildResult): List<PhaseTiming> {
        return result.phases.filter { it.name != "MANIFEST" }
    }

    @Test
    fun `clean build runs every phase and reports misses`() {
        setup()
        val pipeline = buildPipeline()

        val result = runBuild(pipeline, "bld-clean")

        assertEquals(BuildResult.Status.SUCCESS, result.status, "error: ${result.error?.message}")
        assertEquals(0, result.cacheHits)
        assertEquals(8, result.cacheMisses)
        assertTrue(Files.exists(buildRoot.resolve("signed/app.apk")))

        assertEquals(1, resourceCompiler.compileCount)
        assertEquals(1, resourceCompiler.linkCount)
        assertEquals(1, sourceCompiler.compileJavaCount)
        assertEquals(1, dexEngine.dexCount)
        assertEquals(1, packager.packageCount)
        assertEquals(1, packager.zipalignCount)
        assertEquals(1, signer.signCount)

        val buildEvents = events.filter { it.buildId == "bld-clean" }
        assertFalse(buildEvents.any { it is CacheHitEvent })
        assertTrue(buildEvents.any { it is CacheMissEvent })
    }

    @Test
    fun `unchanged rebuild skips every phase and reuses artifacts`() {
        setup()
        val pipeline = buildPipeline()
        runBuild(pipeline, "bld-clean")

        val result = runBuild(pipeline, "bld-warm")

        assertEquals(BuildResult.Status.SUCCESS, result.status)
        assertEquals(8, result.cacheHits)
        assertEquals(0, result.cacheMisses)
        toolPhases(result).forEach {
            assertTrue(it.cacheHit, "${it.name} should be a cache hit")
            assertEquals(PhaseTiming.PhaseStatus.SKIPPED, it.status)
        }

        assertEquals(1, resourceCompiler.compileCount)
        assertEquals(1, resourceCompiler.linkCount)
        assertEquals(1, sourceCompiler.compileJavaCount)
        assertEquals(1, dexEngine.dexCount)
        assertEquals(1, packager.zipalignCount)

        val buildEvents = events.filter { it.buildId == "bld-warm" }
        assertTrue(buildEvents.any { it is CacheHitEvent })
        assertFalse(buildEvents.any { it is CacheMissEvent })
    }

    @Test
    fun `deterministic rebuild produces byte identical apk`() {
        setup()
        val pipeline = buildPipeline()

        runBuild(pipeline, "bld-clean")
        val cleanApk = Files.readAllBytes(buildRoot.resolve("signed/app.apk"))
        runBuild(pipeline, "bld-warm")
        val warmApk = Files.readAllBytes(buildRoot.resolve("signed/app.apk"))

        assertArrayEquals(cleanApk, warmApk)
    }

    @Test
    fun `changed source invalidates only downstream phases`() {
        setup()
        val pipeline = buildPipeline()
        runBuild(pipeline, "bld-clean")
        runBuild(pipeline, "bld-warm")

        resourceCompiler.resetCounts()
        sourceCompiler.resetCounts()
        dexEngine.resetCounts()
        packager.resetCounts()
        signer.resetCounts()
        writeMainActivity("package com.hbe.testapp;\npublic class MainActivity { public void onStart() {} }")

        val result = runBuild(pipeline, "bld-changed")

        assertEquals(BuildResult.Status.SUCCESS, result.status)
        assertEquals(3, result.cacheHits)   // RESOURCE_MERGE, RESOURCE_COMPILE, RESOURCE_LINK unchanged
        assertEquals(5, result.cacheMisses) // JAVA_COMPILE, DEX, PACKAGE, ALIGN, SIGN

        val byName = result.phases.associateBy { it.name }
        assertTrue(byName.getValue("RESOURCE_COMPILE").cacheHit)
        assertTrue(byName.getValue("RESOURCE_LINK").cacheHit)
        assertFalse(byName.getValue("JAVA_COMPILE").cacheHit)
        assertFalse(byName.getValue("DEX").cacheHit)
        assertFalse(byName.getValue("PACKAGE").cacheHit)
        assertFalse(byName.getValue("SIGN").cacheHit)

        assertEquals(0, resourceCompiler.compileCount)
        assertEquals(0, resourceCompiler.linkCount)
        assertEquals(1, sourceCompiler.compileJavaCount)
        assertEquals(1, dexEngine.dexCount)
        assertFalse(sourceCompiler.sawStaleOutput, "re-run must not see leftover class files from the previous build")
    }

    @Test
    fun `warm cache restores artifacts when build dir deleted`() {
        setup()
        val pipeline = buildPipeline()
        runBuild(pipeline, "bld-clean")
        val cleanApk = Files.readAllBytes(buildRoot.resolve("signed/app.apk"))

        Files.walk(buildRoot).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }

        val restored = runBuild(pipeline, "bld-restored")

        assertEquals(BuildResult.Status.SUCCESS, restored.status)
        assertEquals(8, restored.cacheHits)
        assertEquals(0, restored.cacheMisses)
        assertArrayEquals(cleanApk, Files.readAllBytes(buildRoot.resolve("signed/app.apk")))
        assertTrue(restored.metadata["reportPath"] != null)
    }

    @Test
    fun `produces graph visualization and cache report`() {
        setup()
        val pipeline = buildPipeline()

        val result = runBuild(pipeline, "bld-clean")

        val graph = result.metadata["graph"] as String
        assertTrue(graph.contains("JAVA_COMPILE"))
        assertTrue(graph.contains("DEX"))
        assertTrue(graph.contains("->"))

        val reportPath = buildRoot.resolve("incremental-report.txt")
        assertTrue(Files.exists(reportPath))
        val report = String(Files.readAllBytes(reportPath))
        assertTrue(report.contains("TASK GRAPH"))
        assertTrue(report.contains("CACHE STATISTICS"))
        assertTrue(report.contains("INCREMENTAL TIMINGS"))
        assertTrue(report.contains("Cache misses:          8"))
    }

    @Test
    fun `phase executor selects incremental pipeline when requested`() {
        setup()
        val incremental = RecordingPipeline("inc")
        val default = RecordingPipeline("def")
        val executor = PhaseExecutor(logger, default, incremental)
        val context = BuildContextImpl("bld", BuildRequest(projectDir = projectDir.toString(), incremental = true), EngineConfig())

        val result = executor.executeBuild(context)

        assertEquals("inc", result.buildId)
        assertEquals(1, incremental.calls)
        assertEquals(0, default.calls)
    }

    @Test
    fun `phase executor uses default pipeline when incremental disabled`() {
        setup()
        val incremental = RecordingPipeline("inc")
        val default = RecordingPipeline("def")
        val executor = PhaseExecutor(logger, default, incremental)
        val context = BuildContextImpl("bld", BuildRequest(projectDir = projectDir.toString(), incremental = false), EngineConfig())

        val result = executor.executeBuild(context)

        assertEquals("def", result.buildId)
        assertEquals(0, incremental.calls)
        assertEquals(1, default.calls)
    }

    private class RecordingPipeline(private val id: String) : BuildPipeline {
        var calls = 0
        override fun execute(context: BuildContext): BuildResult {
            calls++
            return BuildResult(status = BuildResult.Status.SUCCESS, buildId = id)
        }
    }

    private class FakeSdkManager : SdkManager {
        override fun resolveSdk(compileSdk: Int, buildToolsVersion: String?): SdkResolution {
            return SdkResolution(
                sdkRoot = Path.of("/sdk"),
                platformDir = Path.of("/sdk/platforms/android-35"),
                buildToolsDir = Path.of("/sdk/build-tools/35.0.0"),
                jdkHome = Path.of("/sdk/jdk")
            )
        }
        override fun doctor() = SdkDiagnosis(true, true)
        override fun downloadPlatform(apiLevel: Int) {}
        override fun downloadBuildTools(version: String) {}
        override fun getSdkPath() = null
        override fun getJdkPath() = null
        override fun getToolPath(toolName: String) = null
        override fun findTool(toolName: String) = null
        override fun listInstalledSdk() = SdkEnvironment(null, listOf(35), listOf("35.0.0"), true, false)
        override fun listInstalledTools() = emptyList<ToolInfo>()
        override fun validateEnvironment() = EnvironmentReport(
            osName = "",
            osArch = "",
            osVersion = "",
            isTermux = false,
            isGitHubActions = false,
            java = ToolInfo(name = "", displayName = "", version = null, path = null, available = false),
            androidSdk = null,
            tools = emptyMap(),
            errors = emptyList(),
            warnings = emptyList()
        )
    }

    private class FakeResourceCompiler : ResourceCompiler {
        var compileCount = 0
        var linkCount = 0
        fun resetCounts() { compileCount = 0; linkCount = 0 }

        override fun compile(resDir: Path, outputDir: Path): List<Path> {
            compileCount++
            Files.createDirectories(outputDir)
            val flat = outputDir.resolve("layout_activity_main.flat")
            Files.write(flat, byteArrayOf(1, 2, 3))
            return listOf(flat)
        }
        override fun link(flatFiles: List<Path>, manifest: Path, outputDir: Path, compileSdk: Int, extraPackages: List<String>): ResourceBundle {
            linkCount++
            Files.createDirectories(outputDir.resolve("resources"))
            Files.createDirectories(outputDir.resolve("gen/com/hbe/testapp"))
            Files.write(outputDir.resolve("resources/resources.arsc"), byteArrayOf(10, 11))
            Files.write(outputDir.resolve("resources/AndroidManifest.xml"), "manifest".toByteArray())
            Files.write(outputDir.resolve("gen/com/hbe/testapp/R.java"), "package com.hbe.testapp; class R {}".toByteArray())
            return ResourceBundle(
                resourcesArsc = outputDir.resolve("resources/resources.arsc"),
                rJava = outputDir.resolve("gen/com/hbe/testapp/R.java"),
                manifest = outputDir.resolve("resources/AndroidManifest.xml"),
                configurations = setOf("default")
            )
        }
        override fun mergeManifests(manifests: List<ManifestSource>, outputDir: Path): Path = manifests[0].path
        override fun mergeResources(outputDir: Path, appResDir: Path, libraryResDirs: List<Path>) {
            Files.createDirectories(outputDir)
        }
        override fun linkProto(flatFiles: List<Path>, manifest: Path, outputDir: Path, compileSdk: Int, extraPackages: List<String>): ResourceBundle {
            Files.createDirectories(outputDir.resolve("resources"))
            Files.createDirectories(outputDir.resolve("gen/com/hbe/testapp"))
            Files.write(outputDir.resolve("resources/resources.pb"), byteArrayOf(10, 11))
            Files.write(outputDir.resolve("resources/AndroidManifest.xml"), "manifest".toByteArray())
            Files.write(outputDir.resolve("gen/com/hbe/testapp/R.java"), "package com.hbe.testapp; class R {}".toByteArray())
            val pb = outputDir.resolve("resources/resources.pb")
            return ResourceBundle(
                resourcesArsc = pb,
                rJava = outputDir.resolve("gen/com/hbe/testapp/R.java"),
                manifest = outputDir.resolve("resources/AndroidManifest.xml"),
                configurations = setOf("default"),
                resourcesPb = pb
            )
        }
    }

    private class FakeSourceCompiler : SourceCompiler {
        var compileJavaCount = 0
        var sawStaleOutput = false
        fun resetCounts() { compileJavaCount = 0 }
        override fun compileJava(sources: Set<Path>, classpath: Classpath, outputDir: Path): Set<Path> {
            compileJavaCount++
            if (Files.exists(outputDir) && Files.list(outputDir).use { it.findFirst().isPresent }) {
                sawStaleOutput = true
            }
            Files.createDirectories(outputDir.resolve("com/hbe/testapp"))
            val clazz = outputDir.resolve("com/hbe/testapp/MainActivity.class")
            Files.write(clazz, byteArrayOf(20, 21))
            return setOf(clazz)
        }
        override fun compileKotlin(sources: Set<Path>, classpath: Classpath, outputDir: Path, useCompose: Boolean, kotlinVersion: String): Set<Path> {
            return emptySet()
        }
        override fun batchCompileJava(allSources: Set<Path>, classpath: Classpath, outputDir: Path, batchSize: Int): Set<Path> {
            return compileJava(allSources, classpath, outputDir)
        }
        override fun batchCompileKotlin(allSources: Set<Path>, classpath: Classpath, outputDir: Path, useCompose: Boolean, batchSize: Int, kotlinVersion: String): Set<Path> {
            return emptySet()
        }
    }

    private class FakeDexEngine : DexEngine {
        var dexCount = 0
        fun resetCounts() { dexCount = 0 }
        override fun dex(classFiles: Set<Path>, config: DexConfig): DexOutput {
            dexCount++
            Files.createDirectories(config.outputDir)
            Files.write(config.outputDir.resolve("classes.dex"), byteArrayOf(30, 31))
            return DexOutput(dexFiles = listOf(config.outputDir.resolve("classes.dex")), totalMethodCount = 100, dexFileCount = 1)
        }
        override fun r8(classFiles: Set<Path>, config: DexConfig, proguardRules: Path?) = dex(classFiles, config)
        override fun computeMethodCount(classFiles: Set<Path>): Int = 100
        override fun buildMainDexList(classFiles: Set<Path>, config: DexConfig): List<Path> = emptyList()
    }

    private class FakePackager : Packager {
        var packageCount = 0
        var zipalignCount = 0
        fun resetCounts() { packageCount = 0; zipalignCount = 0 }
        override fun packageApk(dexOutput: DexOutput, resources: ResourceBundle, manifest: Path, nativeLibs: List<Path>, assets: List<Path>, kotlinMetadataDir: Path?, outputDir: Path): Path {
            packageCount++
            Files.createDirectories(outputDir)
            val apk = outputDir.resolve("app.apk")
            Files.write(apk, byteArrayOf(40, 41, 42))
            return apk
        }
        override fun zipalign(apkFile: Path): Path {
            zipalignCount++
            val aligned = apkFile.parent.resolve("app-aligned.apk")
            Files.write(aligned, Files.readAllBytes(apkFile))
            return aligned
        }
        override fun packageAab(baseModuleDir: Path, outputAab: Path): Path {
            packageCount++
            Files.createDirectories(outputAab.parent ?: outputAab)
            Files.write(outputAab, byteArrayOf(50, 51))
            return outputAab
        }
    }

    private class FakeSigner : Signer {
        var signCount = 0
        fun resetCounts() { signCount = 0 }
        override fun sign(apkFile: Path, config: com.hbe.api.dto.SigningConfig): SignedApk {
            signCount++
            return SignedApk(apkPath = apkFile, sizeBytes = Files.size(apkFile), aligned = true, v1Signed = true, v2Signed = true, v3Signed = true)
        }
        override fun verify(apkFile: Path) = SignatureInfo(isSigned = true, v1Signed = true, v2Signed = true, v3Signed = true)
        override fun generateDebugKeystore(keystorePath: Path): Path = keystorePath
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

    private object NoopToolRunner : ToolRunner {
        override fun run(tool: String, args: List<String>, options: ToolOptions): ToolResult {
            return ToolResult(0, "", "", 0, true)
        }
    }
}
