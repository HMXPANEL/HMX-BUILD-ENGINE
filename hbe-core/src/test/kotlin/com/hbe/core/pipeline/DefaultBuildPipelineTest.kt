package com.hbe.core.pipeline

import com.hbe.api.*
import com.hbe.api.dto.BuildRequest
import com.hbe.api.dto.BuildResult
import com.hbe.api.dto.SigningConfig
import com.hbe.api.event.*
import com.hbe.api.exception.ResourceException
import com.hbe.core.BuildContextImpl
import com.hbe.core.event.EventEmittingToolRunner
import com.hbe.core.event.InMemoryBuildEventBus
import io.mockk.Runs
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class DefaultBuildPipelineTest {

    private val logger = com.hbe.core.DefaultLogger()
    private val fileSystem = mockk<FileSystem>()
    private val sdkManager = mockk<SdkManager>()
    private val resourceCompiler = mockk<ResourceCompiler>()
    private val sourceCompiler = mockk<SourceCompiler>()
    private val dexEngine = mockk<DexEngine>()
    private val packager = mockk<Packager>()
    private val signer = mockk<Signer>()
    private val toolRunnerMock = mockk<ToolRunner>()

    private val projectDir = Path.of("/tmp/hbe-test-project")
    private val manifest = projectDir.resolve("AndroidManifest.xml")
    private val resDir = projectDir.resolve("res")
    private val sdkRoot = Path.of("/tmp/hbe-sdk")
    private val platformDir = sdkRoot.resolve("platforms/android-35")
    private val buildToolsDir = sdkRoot.resolve("build-tools/35.0.0")
    private val jdkHome = Path.of("/tmp/hbe-jdk")

    private fun buildPipeline(eventBus: BuildEventBus = InMemoryBuildEventBus()): DefaultBuildPipeline {
        return DefaultBuildPipeline(
            sdkManager = sdkManager,
            resourceCompiler = resourceCompiler,
            sourceCompiler = sourceCompiler,
            dexEngine = dexEngine,
            packager = packager,
            signer = signer,
            toolRunner = EventEmittingToolRunner(toolRunnerMock, eventBus),
            fileSystem = fileSystem,
            eventBus = eventBus,
            logger = logger
        )
    }

    private fun stubHappyPath(
        eventBus: BuildEventBus = InMemoryBuildEventBus(),
        compileThrows: Boolean = false
    ): Triple<DefaultBuildPipeline, BuildEventBus, MutableList<BuildEvent>> {
        every { fileSystem.exists(any()) } returns false
        every { fileSystem.exists(manifest) } returns true
        every { fileSystem.exists(resDir) } returns true
        every { fileSystem.metadata(resDir) } returns FileMetadata(resDir, 0, 0, true, false, false)
        every { fileSystem.createDirectories(any()) } just Runs
        every { fileSystem.walkFiles(any(), any()) } returns emptyList()
        every { fileSystem.size(any()) } returns 1024L

        every { sdkManager.listInstalledSdk() } returns SdkEnvironment(
            sdkRoot = sdkRoot, platforms = listOf(35), buildToolsVersions = listOf("35.0.0"),
            platformToolsAvailable = true, ndkAvailable = false
        )
        every { sdkManager.resolveSdk(35) } returns SdkResolution(
            sdkRoot = sdkRoot, platformDir = platformDir, buildToolsDir = buildToolsDir, jdkHome = jdkHome
        )

        val flatFile = projectDir.resolve("build/hbe/res/flat/layout_activity_main.flat")
        if (compileThrows) {
            every { resourceCompiler.compile(resDir, any()) } throws ResourceException(
                message = "aapt2 compile failed",
                details = listOf("res/values/strings.xml:3: error: <item> has an unexpected tag")
            )
        } else {
            every { resourceCompiler.compile(resDir, any()) } returns listOf(flatFile)
        }
        val bundle = ResourceBundle(
            resourcesArsc = projectDir.resolve("build/hbe/res/link/resources/resources.arsc"),
            rJava = projectDir.resolve("build/hbe/res/link/gen/com/hbe/testapp/R.java"),
            manifest = projectDir.resolve("build/hbe/res/link/resources/AndroidManifest.xml"),
            configurations = setOf("default", "layout", "values"),
            resourceIds = mapOf("layout_activity_main" to 2130771968)
        )
        every { resourceCompiler.link(any(), any(), any(), 35) } returns bundle

        val classFile = projectDir.resolve("build/hbe/classes/com/hbe/testapp/MainActivity.class")
        every { sourceCompiler.compileJava(any(), any(), any()) } returns setOf(classFile)
        every { sourceCompiler.compileKotlin(any(), any(), any(), any()) } returns emptySet()

        val dexFile = projectDir.resolve("build/hbe/dex/classes.dex")
        every { dexEngine.dex(any(), any()) } returns DexOutput(
            dexFiles = listOf(dexFile), totalMethodCount = 128, dexFileCount = 1
        )

        val apkFile = projectDir.resolve("build/hbe/app.apk")
        every { packager.packageApk(any(), any(), any(), any(), any(), any(), any()) } returns apkFile
        val alignedApk = projectDir.resolve("build/hbe/app-aligned.apk")
        every { packager.zipalign(apkFile) } returns alignedApk
        every { signer.sign(alignedApk, any()) } returns SignedApk(
            apkPath = alignedApk, sizeBytes = 512, aligned = true, v1Signed = true, v2Signed = true
        )

        val events = mutableListOf<BuildEvent>()
        eventBus.subscribe { events.add(it) }

        return Triple(buildPipeline(eventBus), eventBus, events)
    }

    @Test
    fun `successful build returns SUCCESS with apk path`() {
        val (pipeline, _, events) = stubHappyPath()
        val context = BuildContextImpl("bld-test", BuildRequest(projectDir = projectDir.toString()), com.hbe.api.EngineConfig())

        val result = pipeline.execute(context)

        assertEquals(BuildResult.Status.SUCCESS, result.status)
        assertEquals(projectDir.resolve("build/hbe/app-aligned.apk").toString(), result.apkPath)
        assertEquals(1024L, result.apkSizeBytes)
        assertTrue(result.phases.any { it.name == "RESOURCE_LINK" })
        assertTrue(result.phases.any { it.name == "DEX" })
    }

    @Test
    fun `successful build publishes lifecycle events in order`() {
        val (pipeline, _, events) = stubHappyPath()
        val context = BuildContextImpl("bld-test", BuildRequest(projectDir = projectDir.toString()), com.hbe.api.EngineConfig())

        pipeline.execute(context)

        val types = events.map { it::class.simpleName }
        assertEquals("BuildStartedEvent", types.first())
        assertEquals("BuildFinishedEvent", types.last())
        assertTrue(types.contains("ResourceCompilationStartedEvent"))
        assertTrue(types.contains("ResourceLinkStartedEvent"))
        assertTrue(types.contains("JavaCompilationStartedEvent"))
        assertTrue(types.contains("DexGenerationStartedEvent"))
        assertTrue(types.contains("PackagingStartedEvent"))
        assertTrue(types.contains("SigningStartedEvent"))
        assertTrue(types.contains("PhaseFinishedEvent"))
    }

    @Test
    fun `resource failure returns FAILURE with phase and code`() {
        val (pipeline, _, events) = stubHappyPath(compileThrows = true)
        val context = BuildContextImpl("bld-test", BuildRequest(projectDir = projectDir.toString()), com.hbe.api.EngineConfig())

        val result = pipeline.execute(context)

        assertEquals(BuildResult.Status.FAILURE, result.status)
        assertEquals("RESOURCE_COMPILE", result.error?.phase)
        assertEquals("RESOURCE_ERROR", result.error?.code)
        assertTrue(events.any { it is BuildErrorEvent })
        assertFalse(events.any { it is BuildFinishedEvent })
    }

    @Test
    fun `cancelled build returns failure with cancelled error`() {
        val (pipeline, _, events) = stubHappyPath()
        val token = com.hbe.core.CancellationTokenImpl()
        token.cancel()
        val context = BuildContextImpl(
            "bld-test", BuildRequest(projectDir = projectDir.toString()),
            config = com.hbe.api.EngineConfig(),
            cancellationToken = token
        )

        val result = pipeline.execute(context)

        assertEquals(BuildResult.Status.FAILURE, result.status)
        assertEquals("CANCELLED", result.error?.code)
        assertTrue(events.any { it is BuildCancelledEvent })
    }

    @Test
    fun `missing manifest returns failure`() {
        val (pipeline, _, _) = stubHappyPath()
        every { fileSystem.exists(manifest) } returns false
        val context = BuildContextImpl("bld-test", BuildRequest(projectDir = projectDir.toString()), com.hbe.api.EngineConfig())

        val result = pipeline.execute(context)

        assertEquals(BuildResult.Status.FAILURE, result.status)
        assertEquals("MANIFEST_NOT_FOUND", result.error?.code)
    }

    @Test
    fun `debug variant defaults to debug signing`() {
        val (pipeline, _, _) = stubHappyPath()
        val context = BuildContextImpl("bld-test", BuildRequest(projectDir = projectDir.toString(), variant = "debug"), com.hbe.api.EngineConfig())
        pipeline.execute(context)
        verify { signer.sign(any(), match { it.type == SigningConfig.SigningType.DEBUG }) }
    }
}
