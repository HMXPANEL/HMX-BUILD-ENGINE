package com.hbe.core.pipeline

import com.hbe.api.*
import com.hbe.api.dto.ArtifactKey
import com.hbe.api.dto.BuildError
import com.hbe.api.dto.BuildRequest
import com.hbe.api.dto.BuildResult
import com.hbe.api.dto.PhaseTiming
import com.hbe.api.dto.SigningConfig
import com.hbe.api.event.*
import com.hbe.api.exception.BuildException
import com.hbe.core.BuildCancelledException
import com.hbe.core.event.BuildProgressTracker
import com.hbe.core.event.EventEmittingToolRunner
import com.hbe.core.project.GradleMetadata
import com.hbe.core.project.ManifestMerger
import com.hbe.graph.BuildEdge
import com.hbe.graph.BuildGraph
import com.hbe.graph.BuildNode
import com.hbe.graph.NodeType
import com.hbe.scheduler.TaskScheduler
import java.io.BufferedOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Incremental build pipeline.
 *
 * Builds a task graph of the standard phases and runs it through the TaskScheduler.
 * Every phase is keyed by a content fingerprint (source file hashes + phase params +
 * upstream fingerprints) in the CacheManager: an unchanged phase is skipped and its
 * artifacts reused, publishing CacheHitEvent/CacheMissEvent per phase.
 */
class IncrementalBuildPipeline(
    private val sdkManager: SdkManager,
    private val resourceCompiler: ResourceCompiler,
    private val sourceCompiler: SourceCompiler,
    private val dexEngine: DexEngine,
    private val packager: Packager,
    private val signer: Signer,
    private val toolRunner: ToolRunner,
    private val fileSystem: FileSystem,
    private val eventBus: BuildEventBus,
    private val logger: Logger,
    private val cacheManager: CacheManager,
    private val scheduler: TaskScheduler,
    private val memoryMonitor: MemoryMonitor,
    private val projectDependencies: ProjectDependencies? = null
) : BuildPipeline {

    private val state = mutableMapOf<String, Any>()
    private val progressTracker = BuildProgressTracker(logger).also {
        it.attach(eventBus)
    }

    override fun execute(context: BuildContext): BuildResult {
        val buildId = context.buildId
        val request = context.request
        val config = context.config
        val token = context.cancellationToken
        val buildStart = System.currentTimeMillis()
        state.clear()

        if (toolRunner is EventEmittingToolRunner) toolRunner.buildId = buildId

        eventBus.publish(BuildStartedEvent(
            buildId = buildId,
            metadata = mapOf("projectDir" to request.projectDir, "variant" to request.variant)
        ))

        try {
            token.throwIfCancelled()

            val projectRoot = findProjectRoot(Path.of(request.projectDir))
            val originalManifest = findManifest(projectRoot)
                ?: throw BuildException(
                    message = "AndroidManifest.xml not found in $projectRoot",
                    errorCode = "MANIFEST_NOT_FOUND",
                    suggestion = "Ensure the project contains an AndroidManifest.xml"
                )
            val resDir = findResDir(projectRoot)
            val compileSdk = request.compileSdk ?: resolveDefaultCompileSdk()
            val minSdk = request.minSdk ?: 24
            val variant = request.variant
            val projectId = Path.of(request.projectDir).toAbsolutePath().normalize().toString()

            val resolution = resolveSdk(request, config, compileSdk)
            val androidJar = resolution.androidJar
            val buildRoot = resolveBuildRoot(projectRoot, config)
            fileSystem.createDirectories(buildRoot)

            // When the module declares a namespace (build.gradle) but the manifest
            // carries no package attribute, inject it into a build-dir copy so
            // aapt2 link and R generation use the right package. The project
            // source tree is never modified.
            val deps = projectDependencies
            val namespace = deps?.namespace ?: GradleMetadata.findNamespace(projectRoot)
            val manifest = prepareManifest(originalManifest, buildRoot, namespace)

            // Merge library AAR manifests into the app manifest so that components
            // declared by dependencies (e.g. androidx.startup.InitializationProvider,
            // CoreComponentFactory, ProfileInstallReceiver) are present in the APK.
            val mergedManifest = if (deps != null && deps.libraryManifests.isNotEmpty()) {
                val applicationId = deps.namespace ?: request.projectDir.substringAfterLast("/")
                ManifestMerger(fileSystem, logger).merge(manifest, deps.libraryManifests, applicationId)
            } else {
                manifest
            }
            val toolKey = "${resolution.buildToolsDir.fileName}/platforms/${resolution.platformDir.fileName}"
            val baseParams = mapOf("scheme" to "v1", "tool" to toolKey, "compileSdk" to compileSdk.toString())

            val fingerprints = mutableMapOf<String, String>()
            val mergedRes = buildRoot.resolve("res/merged")
            val flatOut = buildRoot.resolve("res/flat")
            val libFlatDirs = deps?.libraryResDirs?.mapIndexed { i, _ -> buildRoot.resolve("res/lib-$i") } ?: emptyList()
            val linkOut = buildRoot.resolve("res/link")
            val javaClasses = buildRoot.resolve("classes/java")
            val kotlinClasses = buildRoot.resolve("classes/kotlin")
            val dexOut = buildRoot.resolve("dex")
            val packageOut = buildRoot.resolve("package")
            val alignOut = buildRoot.resolve("align")
            val signedOut = buildRoot.resolve("signed")

            val manifestTask = TaskSpec(
                node = BuildNode("MANIFEST", NodeType.MANIFEST_MERGE, label = "MANIFEST"),
                params = emptyMap(),
                upstreamIds = emptyList(),
                inputFiles = { listOf(mergedManifest) },
                outputDir = null,
                run = {},
                restore = {},
                outputReady = { true },
                fingerprintOnly = true
            )

            val tasks = mutableListOf(manifestTask)
            val sourceNodeIds = mutableListOf<String>()

            val hasRes = resDir != null || deps?.libraryResDirs?.isNotEmpty() == true
            if (hasRes) {
                tasks += TaskSpec(
                    node = BuildNode("RESOURCE_MERGE", NodeType.RES_COMPILE, label = "RESOURCE_MERGE"),
                    params = baseParams,
                    upstreamIds = emptyList(),
                    inputFiles = {
                        val appRes = resDir?.let { fileSystem.walkFiles(it, "*") } ?: emptyList()
                        val libRes = deps?.libraryResDirs?.flatMap { fileSystem.walkFiles(it, "*") } ?: emptyList()
                        appRes + libRes
                    },
                    outputDir = mergedRes,
                    run = {
                        val start = System.currentTimeMillis()
                        eventBus.publish(ResourceCompilationStartedEvent(buildId))
                        val libRes = deps?.libraryResDirs ?: emptyList()
                        resourceCompiler.mergeResources(mergedRes, resDir ?: mergedRes, libRes)
                        state["mergedRes"] = mergedRes
                        eventBus.publish(ResourceCompilationFinishedEvent(buildId,
                            durationMs = System.currentTimeMillis() - start,
                            metadata = mapOf("mergedRes" to mergedRes.toString())))
                    },
                    restore = { state["mergedRes"] = mergedRes },
                    outputReady = { fileSystem.exists(mergedRes) && fileSystem.walkFiles(mergedRes, "*").isNotEmpty() }
                )

                tasks += TaskSpec(
                    node = BuildNode("RESOURCE_COMPILE", NodeType.RES_COMPILE, label = "RESOURCE_COMPILE"),
                    params = baseParams,
                    upstreamIds = listOf("RESOURCE_MERGE"),
                    inputFiles = { fileSystem.walkFiles(mergedRes, "*") },
                    outputDir = flatOut,
                    run = {
                        val start = System.currentTimeMillis()
                        val files = resourceCompiler.compile(mergedRes, flatOut)
                        state["flatFiles"] = files
                        eventBus.publish(ResourceCompilationFinishedEvent(buildId,
                            durationMs = System.currentTimeMillis() - start,
                            metadata = mapOf("flatFileCount" to files.size)))
                    },
                    restore = { state["flatFiles"] = collectAllFlats(flatOut, libFlatDirs) },
                    outputReady = { collectAllFlats(flatOut, libFlatDirs).isNotEmpty() }
                )
            }

            tasks += TaskSpec(
                node = BuildNode("RESOURCE_LINK", NodeType.RES_LINK, label = "RESOURCE_LINK"),
                params = baseParams,
                upstreamIds = listOf("MANIFEST") + (if (hasRes) listOf("RESOURCE_COMPILE") else emptyList()),
                inputFiles = { listOf(mergedManifest) },
                outputDir = linkOut,
                run = {
                    val start = System.currentTimeMillis()
                    eventBus.publish(ResourceLinkStartedEvent(buildId))
                    val flatFiles = state["flatFiles"] as? List<Path> ?: emptyList()
                    val bundle = resourceCompiler.link(flatFiles, mergedManifest, linkOut, compileSdk)
                    state["bundle"] = bundle
                    eventBus.publish(ResourceLinkFinishedEvent(buildId,
                        durationMs = System.currentTimeMillis() - start,
                        metadata = mapOf("configurations" to bundle.configurations.size)))
                },
                restore = { state["bundle"] = reconstructBundle(linkOut, mergedManifest) },
                outputReady = { fileSystem.exists(linkOut.resolve("resources/resources.arsc")) }
            )

            val javaSources = collectSources(projectRoot, "java")
            val kotlinSources = collectSources(projectRoot, "kotlin")
            val dependencyClasspath = deps?.classpath ?: emptyList()
            val baseClasspath = Classpath(entries = listOf(androidJar) + dependencyClasspath)

            if (javaSources.isNotEmpty()) {
                sourceNodeIds += "JAVA_COMPILE"
                tasks += TaskSpec(
                    node = BuildNode("JAVA_COMPILE", NodeType.SOURCE_COMPILE, label = "JAVA_COMPILE"),
                    params = baseParams,
                    upstreamIds = listOf("RESOURCE_LINK"),
                    inputFiles = {
                        val rJava = (state["bundle"] as? ResourceBundle)?.rJava
                        javaSources.toList() + (rJava?.let { listOf(it) } ?: emptyList()) + dependencyClasspath
                    },
                    outputDir = javaClasses,
                    run = {
                        val start = System.currentTimeMillis()
                        val rJava = (state["bundle"] as ResourceBundle).rJava
                        val sources = javaSources + setOf(rJava)
                        eventBus.publish(JavaCompilationStartedEvent(buildId,
                            metadata = mapOf("sourceCount" to sources.size)))
                        val compiled = sourceCompiler.compileJava(sources, baseClasspath, javaClasses)
                        state["javaClasses"] = compiled
                        eventBus.publish(JavaCompilationFinishedEvent(buildId,
                            durationMs = System.currentTimeMillis() - start,
                            metadata = mapOf("classFileCount" to compiled.size)))
                    },
                    restore = { state["javaClasses"] = fileSystem.walkFiles(javaClasses, "*.class").toSet() },
                    outputReady = { fileSystem.walkFiles(javaClasses, "*.class").isNotEmpty() }
                )
            }

            if (kotlinSources.isNotEmpty()) {
                sourceNodeIds += "KOTLIN_COMPILE"
                val composeEnabled = request.compose || detectCompose(projectRoot)
                val kotlinVersion = request.kotlinVersion ?: detectKotlinVersion(request)
                tasks += TaskSpec(
                    node = BuildNode("KOTLIN_COMPILE", NodeType.SOURCE_COMPILE, label = "KOTLIN_COMPILE"),
                    params = baseParams + mapOf("compose" to composeEnabled.toString(), "kotlinVersion" to kotlinVersion),
                    upstreamIds = if (javaSources.isNotEmpty()) listOf("JAVA_COMPILE") else listOf("RESOURCE_LINK"),
                    inputFiles = { kotlinSources.toList() + dependencyClasspath },
                    outputDir = kotlinClasses,
                    run = {
                        val start = System.currentTimeMillis()
                        val classpath = if (javaSources.isNotEmpty()) baseClasspath.addOutputDir(javaClasses) else baseClasspath
                        eventBus.publish(KotlinCompilationStartedEvent(buildId,
                            metadata = mapOf("sourceCount" to kotlinSources.size)))
                        val compiled = sourceCompiler.compileKotlin(kotlinSources, classpath, kotlinClasses, composeEnabled, kotlinVersion)
                        state["kotlinClasses"] = compiled
                        eventBus.publish(KotlinCompilationFinishedEvent(buildId,
                            durationMs = System.currentTimeMillis() - start,
                            metadata = mapOf("classFileCount" to compiled.size)))
                    },
                    restore = { state["kotlinClasses"] = fileSystem.walkFiles(kotlinClasses, "*.class").toSet() },
                    outputReady = { fileSystem.walkFiles(kotlinClasses, "*.class").isNotEmpty() }
                )
            }

            if (sourceNodeIds.isEmpty()) {
                throw BuildException(
                    message = "No Java or Kotlin sources found",
                    errorCode = "NO_SOURCES",
                    suggestion = "Add sources under src/main/java or src/main/kotlin"
                )
            }

            tasks += TaskSpec(
                node = BuildNode("DEX", NodeType.DEX, label = "DEX"),
                params = baseParams + mapOf("minSdk" to minSdk.toString(), "debug" to (variant != "release").toString()),
                upstreamIds = sourceNodeIds,
                inputFiles = { dependencyClasspath },
                outputDir = dexOut,
                run = {
                    val start = System.currentTimeMillis()
                    eventBus.publish(DexGenerationStartedEvent(buildId))
                    val java = (state["javaClasses"] as? Set<*>)?.filterIsInstance<Path>()?.toSet() ?: emptySet()
                    val kotlin = (state["kotlinClasses"] as? Set<*>)?.filterIsInstance<Path>()?.toSet() ?: emptySet()
                    val dexInputs = java + kotlin + dependencyClasspath
                    val dexOutput = dexEngine.dex(dexInputs, DexConfig(
                        minSdk = minSdk,
                        debug = variant != "release",
                        outputDir = dexOut,
                        libraryJars = listOf(androidJar),
                        isRelease = variant == "release"
                    ))
                    state["dexOutput"] = dexOutput
                    eventBus.publish(DexGenerationFinishedEvent(buildId,
                        durationMs = System.currentTimeMillis() - start,
                        metadata = mapOf("dexFileCount" to dexOutput.dexFileCount,
                            "methodCount" to dexOutput.totalMethodCount)))
                },
                restore = {
                    val dexFiles = fileSystem.walkFiles(dexOut, "*.dex").sorted()
                    state["dexOutput"] = DexOutput(dexFiles = dexFiles, dexFileCount = dexFiles.size)
                },
                outputReady = { fileSystem.walkFiles(dexOut, "*.dex").isNotEmpty() }
            )

            tasks += TaskSpec(
                node = BuildNode("PACKAGE", NodeType.PACKAGE, label = "PACKAGE"),
                params = baseParams + mapOf("libAssets" to (deps?.libraryAssets?.size ?: 0).toString(),
                    "libNative" to (deps?.nativeLibs?.size ?: 0).toString()),
                upstreamIds = listOf("DEX", "RESOURCE_LINK"),
                inputFiles = { (deps?.libraryAssets ?: emptyList()) + (deps?.nativeLibs ?: emptyList()) },
                outputDir = packageOut,
                run = {
                    val start = System.currentTimeMillis()
                    eventBus.publish(PackagingStartedEvent(buildId))
                    val dexOutput = state["dexOutput"] as DexOutput
                    val bundle = state["bundle"] as ResourceBundle
                    val apk = packager.packageApk(dexOutput, bundle, bundle.manifest,
                        nativeLibs = deps?.nativeLibs ?: emptyList(),
                        assets = deps?.libraryAssets ?: emptyList(),
                        outputDir = packageOut)
                    state["apkFile"] = apk
                    eventBus.publish(PackagingFinishedEvent(buildId,
                        durationMs = System.currentTimeMillis() - start,
                        metadata = mapOf("apkSizeBytes" to fileSystem.size(apk))))
                },
                restore = { state["apkFile"] = packageOut.resolve("app.apk") },
                outputReady = { fileSystem.exists(packageOut.resolve("app.apk")) }
            )

            tasks += TaskSpec(
                node = BuildNode("ALIGN", NodeType.ALIGN, label = "ALIGN"),
                params = baseParams,
                upstreamIds = listOf("PACKAGE"),
                inputFiles = { emptyList() },
                outputDir = alignOut,
                run = {
                    val start = System.currentTimeMillis()
                    eventBus.publish(PhaseStartedEvent(buildId, phase = "ALIGN"))
                    val apk = state["apkFile"] as Path
                    val input = alignOut.resolve("app.apk")
                    fileSystem.copy(apk, input)
                    val aligned = packager.zipalign(input)
                    state["alignedApk"] = aligned
                    eventBus.publish(PhaseFinishedEvent(buildId, phase = "ALIGN",
                        durationMs = System.currentTimeMillis() - start))
                },
                restore = { state["alignedApk"] = alignOut.resolve("app-aligned.apk") },
                outputReady = { fileSystem.exists(alignOut.resolve("app-aligned.apk")) }
            )

            val signingConfig = request.signingConfig
                ?: if (variant == "debug") SigningConfig.debug() else SigningConfig.unsigned()

            tasks += TaskSpec(
                node = BuildNode("SIGN", NodeType.SIGN, label = "SIGN"),
                params = baseParams + mapOf("signType" to signingConfig.type.name),
                upstreamIds = listOf("ALIGN"),
                inputFiles = { emptyList() },
                outputDir = signedOut,
                run = {
                    val start = System.currentTimeMillis()
                    eventBus.publish(SigningStartedEvent(buildId,
                        metadata = mapOf("type" to signingConfig.type.name)))
                    val aligned = state["alignedApk"] as Path
                    val input = signedOut.resolve("app.apk")
                    fileSystem.copy(aligned, input)
                    val signed = signer.sign(input, signingConfig)
                    state["signedApk"] = signed
                    eventBus.publish(SigningFinishedEvent(buildId,
                        durationMs = System.currentTimeMillis() - start,
                        metadata = mapOf("type" to signingConfig.type.name)))
                },
                restore = {
                    val apk = signedOut.resolve("app.apk")
                    state["signedApk"] = SignedApk(
                        apkPath = apk, sizeBytes = fileSystem.size(apk),
                        aligned = true, v1Signed = true, v2Signed = true, v3Signed = true
                    )
                },
                outputReady = { fileSystem.exists(signedOut.resolve("app.apk")) }
            )

            val graph = buildGraph(tasks)
            val plan = scheduler.schedule(graph, context)

            val tasksById = tasks.associateBy { it.node.id }
            val fingerprintOnlyIds = tasks.filter { it.fingerprintOnly }.map { it.node.id }.toSet()
            var cacheHits = 0
            var cacheMisses = 0

            val executor: (BuildNode) -> PhaseTiming = { node ->
                token.throwIfCancelled()
                val task = tasksById.getValue(node.id)
                executeTask(task, buildId, projectId, variant, toolKey, fingerprints, state) {
                    if (it) cacheHits++ else cacheMisses++
                }
            }

            val allTimings = scheduler.execute(context, plan, executor)
            val timings = allTimings.filterNot { it.name in fingerprintOnlyIds }
            val totalDuration = System.currentTimeMillis() - buildStart

            val finalApk = applyOutputPath((state["signedApk"] as SignedApk).apkPath, request)
            val cacheStats = cacheManager.stats()

            val report = buildReport(buildId, variant, graph, timings, cacheHits, cacheMisses,
                cacheStats.totalEntries, cacheStats.totalSizeBytes, cacheStats.maxSizeBytes,
                totalDuration, buildRoot, request.incremental)
            fileSystem.writeBytes(buildRoot.resolve("incremental-report.txt"), report.toByteArray())
            appendHistory(buildRoot, buildId, variant, totalDuration, cacheHits, cacheMisses)

            eventBus.publish(BuildFinishedEvent(buildId, durationMs = totalDuration,
                metadata = mapOf(
                    "apkPath" to finalApk.toString(),
                    "cacheHits" to cacheHits,
                    "cacheMisses" to cacheMisses,
                    "phases" to timings.size,
                    "graph" to graph.visualize()
                )))

            return BuildResult(
                status = BuildResult.Status.SUCCESS,
                apkPath = finalApk.toString(),
                apkSizeBytes = fileSystem.size(finalApk),
                phases = timings,
                totalDurationMs = totalDuration,
                buildId = buildId,
                cacheHits = cacheHits,
                cacheMisses = cacheMisses,
                metadata = mapOf(
                    "androidJar" to androidJar.toString(),
                    "graph" to graph.visualize(),
                    "cacheHitRate" to if (cacheHits + cacheMisses > 0) cacheHits.toDouble() / (cacheHits + cacheMisses) else 0.0,
                    "reportPath" to buildRoot.resolve("incremental-report.txt").toString()
                )
            )
        } catch (e: BuildCancelledException) {
            val duration = System.currentTimeMillis() - buildStart
            eventBus.publish(BuildCancelledEvent(buildId, durationMs = duration))
            return BuildResult(
                status = BuildResult.Status.FAILURE,
                error = BuildError(activePhase(), "CANCELLED", "Build was cancelled"),
                phases = emptyList(),
                totalDurationMs = duration,
                buildId = buildId
            )
        } catch (e: BuildException) {
            val duration = System.currentTimeMillis() - buildStart
            val phase = activePhase()
            eventBus.publish(BuildErrorEvent(buildId, phase = phase,
                metadata = mapOf("code" to e.errorCode, "message" to e.message)))
            return BuildResult(
                status = BuildResult.Status.FAILURE,
                error = BuildError(phase, e.errorCode, e.message ?: "Unknown build error",
                    e.suggestion, e.details),
                totalDurationMs = duration,
                buildId = buildId
            )
        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - buildStart
            val phase = activePhase()
            eventBus.publish(BuildErrorEvent(buildId, phase = phase,
                metadata = mapOf("code" to "INTERNAL_ERROR", "message" to (e.message ?: ""))))
            return BuildResult(
                status = BuildResult.Status.FAILURE,
                error = BuildError(phase, "INTERNAL_ERROR", e.message ?: "Unknown build error"),
                totalDurationMs = duration,
                buildId = buildId
            )
        }
    }

    private fun activePhase(): String = state["lastPhase"] as? String ?: "BUILD"

    private fun executeTask(
        task: TaskSpec,
        buildId: String,
        projectId: String,
        variant: String,
        toolVersion: String,
        fingerprints: MutableMap<String, String>,
        state: MutableMap<String, Any>,
        count: (Boolean) -> Unit
    ): PhaseTiming {
        if (task.fingerprintOnly) {
            fingerprints[task.node.id] = computeFingerprint(task, fingerprints)
            return PhaseTiming(task.node.id, PhaseTiming.PhaseStatus.SUCCESS)
        }

        val fingerprint = computeFingerprint(task, fingerprints)
        fingerprints[task.node.id] = fingerprint
        val key = ArtifactKey(task.node.id, projectId, fingerprint, toolVersion, variant)

        val cached = cacheManager.get(key)
        return if (cached != null) {
            count(true)
            val start = System.currentTimeMillis()
            var restored = false
            if (!task.outputReady()) {
                CacheArchive.unzipDir(cached.artifactPath, task.outputDir!!)
                restored = true
            }
            task.restore()
            eventBus.publish(CacheHitEvent(buildId, phase = task.node.id,
                metadata = mapOf("hash" to fingerprint, "restored" to restored)))
            PhaseTiming(task.node.id, PhaseTiming.PhaseStatus.SKIPPED,
                durationMs = System.currentTimeMillis() - start, cacheHit = true)
        } else {
            count(false)
            state["lastPhase"] = task.node.id
            eventBus.publish(CacheMissEvent(buildId, phase = task.node.id,
                metadata = mapOf("hash" to fingerprint)))
            val start = System.currentTimeMillis()
            task.outputDir?.let { fileSystem.deleteRecursively(it) }
            task.run()
            val duration = System.currentTimeMillis() - start
            val archive = fileSystem.createTempFile("hbe-cache-", ".zip")
            CacheArchive.zipDir(task.outputDir!!, archive)
            cacheManager.put(key, archive)
            fileSystem.delete(archive)
            PhaseTiming(task.node.id, PhaseTiming.PhaseStatus.SUCCESS, durationMs = duration)
        }
    }

    private fun computeFingerprint(
        task: TaskSpec,
        upstreamFingerprints: Map<String, String>
    ): String {
        val digester = MessageDigest.getInstance("SHA-256")
        fun update(value: String) {
            digester.update(value.toByteArray())
            digester.update(0)
        }
        task.params.toSortedMap().forEach { (k, v) -> update("$k=$v") }
        task.upstreamIds.sorted().forEach { id ->
            upstreamFingerprints[id]?.let { update("up:$id:$it") }
        }
        task.inputFiles().sortedBy { it.toString() }.forEach { path ->
            val hash = try {
                digestHex(fileSystem.readAllBytes(path))
            } catch (_: Exception) {
                "missing"
            }
            update("file:${path.toString()}:$hash")
        }
        return digestHex(digester.digest())
    }

    private fun buildGraph(tasks: List<TaskSpec>): BuildGraph {
        val nodes = tasks.map { it.node }
        val edges = mutableListOf<BuildEdge>()
        for (task in tasks) {
            for (upstream in task.upstreamIds) {
                if (nodes.any { it.id == upstream }) {
                    edges += BuildEdge(upstream, task.node.id)
                }
            }
        }
        val issues = BuildGraph(nodes = nodes, edges = edges).validate()
        if (issues.isNotEmpty()) {
            throw BuildException(
                message = "Invalid build graph: ${issues.joinToString("; ")}",
                errorCode = "INVALID_GRAPH"
            )
        }
        return BuildGraph(nodes = nodes, edges = edges)
    }

    private fun reconstructBundle(linkOut: Path, manifest: Path): ResourceBundle {
        val resources = linkOut.resolve("resources")
        val rJava = fileSystem.walkFiles(linkOut.resolve("gen"), "R.java").firstOrNull()
            ?: linkOut.resolve("gen/R.java")
        val resDir = resources.resolve("res")
        return ResourceBundle(
            resourcesArsc = resources.resolve("resources.arsc"),
            rJava = rJava,
            compiledResDirectories = if (fileSystem.exists(resDir)) listOf(resDir) else emptyList(),
            manifest = resources.resolve("AndroidManifest.xml"),
            configurations = setOf("default"),
            resourceIds = emptyMap()
        )
    }

    private fun buildReport(
        buildId: String,
        variant: String,
        graph: BuildGraph,
        timings: List<PhaseTiming>,
        cacheHits: Int,
        cacheMisses: Int,
        totalEntries: Long,
        totalSizeBytes: Long,
        maxSizeBytes: Long,
        totalDurationMs: Long,
        buildRoot: Path,
        incremental: Boolean
    ): String {
        val total = cacheHits + cacheMisses
        val hitRate = if (total > 0) cacheHits.toDouble() / total else 0.0
        val sb = StringBuilder()
        sb.appendLine("HMX Build Engine — Incremental Build Report")
        sb.appendLine("Build: $buildId | variant=$variant | incremental=$incremental")
        sb.appendLine("============================================================")
        sb.appendLine()
        sb.appendLine("TASK GRAPH")
        sb.appendLine("----------")
        sb.append(graph.visualize())
        sb.appendLine()
        sb.appendLine("CACHE STATISTICS")
        sb.appendLine("----------------")
        sb.appendLine("Cache hits:            $cacheHits")
        sb.appendLine("Cache misses:          $cacheMisses")
        sb.appendLine("Cache hit rate:        ${"%.1f".format(hitRate * 100)}%")
        sb.appendLine("Cache entries:         $totalEntries")
        sb.appendLine("Cache size (bytes):    $totalSizeBytes")
        sb.appendLine("Cache max (bytes):     $maxSizeBytes")
        sb.appendLine()
        sb.appendLine("INCREMENTAL TIMINGS")
        sb.appendLine("-------------------")
        for (timing in timings) {
            val status = if (timing.cacheHit) "HIT " else "MISS"
            sb.appendLine("%-18s %s %6dms".format(timing.name, status, timing.durationMs))
        }
        sb.appendLine("TOTAL                    ${totalDurationMs}ms")
        sb.appendLine()
        val comparison = compareWithPrevious(buildRoot, variant, totalDurationMs, cacheHits, cacheMisses)
        if (comparison != null) {
            sb.appendLine("PERFORMANCE COMPARISON (vs previous build)")
            sb.appendLine("------------------------------------------")
            sb.appendLine(comparison)
            sb.appendLine()
        }
        return sb.toString()
    }

    private fun compareWithPrevious(
        buildRoot: Path,
        variant: String,
        currentMs: Long,
        currentHits: Int,
        currentMisses: Int
    ): String? {
        val history = buildRoot.resolve("incremental-history.log")
        if (!fileSystem.exists(history)) return null
        val lines = String(fileSystem.readAllBytes(history)).lines()
            .filter { it.isNotBlank() }
        if (lines.isEmpty()) return null
        val previous = lines.last().split(",")
        if (previous.size < 4 || previous[1] != variant) return null
        val prevMs = previous[2].toLongOrNull() ?: return null
        val speedup = if (currentMs > 0) prevMs.toDouble() / currentMs else 0.0
        return "Previous: ${previous[0]} (${prevMs}ms, hits=${previous[3]})\n" +
            "Current:  ${currentMs}ms (hits=$currentHits, misses=$currentMisses)\n" +
            "Speedup:  ${"%.1f".format(speedup)}x"
    }

    private fun appendHistory(
        buildRoot: Path,
        buildId: String,
        variant: String,
        totalMs: Long,
        hits: Int,
        misses: Int
    ) {
        val history = buildRoot.resolve("incremental-history.log")
        val existing = if (fileSystem.exists(history)) String(fileSystem.readAllBytes(history)) else ""
        val line = "$buildId,$variant,$totalMs,$hits,$misses\n"
        fileSystem.writeBytes(history, (existing + line).toByteArray())
    }

    private fun resolveSdk(request: BuildRequest, config: EngineConfig, compileSdk: Int): SdkResolution {
        return try {
            sdkManager.resolveSdk(compileSdk)
        } catch (e: com.hbe.api.exception.SdkException) {
            if (!config.autoDownloadSdk) throw e
            logger.info("SDK platform missing — downloading", mapOf("compileSdk" to compileSdk.toString()))
            sdkManager.downloadPlatform(compileSdk)
            val btVersion = request.buildToolsVersion ?: "34.0.0"
            sdkManager.downloadBuildTools(btVersion)
            sdkManager.resolveSdk(compileSdk, btVersion)
        }
    }

    private fun resolveDefaultCompileSdk(): Int {
        return sdkManager.listInstalledSdk().platforms.maxOrNull() ?: 35
    }

    private fun findProjectRoot(projectDir: Path): Path {
        val appDir = projectDir.resolve("app")
        return if (isDirectory(appDir) &&
            (isDirectory(appDir.resolve("src")) || isDirectory(appDir.resolve("res")) || fileSystem.exists(appDir.resolve("AndroidManifest.xml")))) {
            appDir
        } else projectDir
    }

    private fun findManifest(root: Path): Path? {
        return listOf(
            root.resolve("AndroidManifest.xml"),
            root.resolve("src/main/AndroidManifest.xml")
        ).firstOrNull { fileSystem.exists(it) }
    }

    private fun findResDir(root: Path): Path? {
        return listOf(
            root.resolve("res"),
            root.resolve("src/main/res")
        ).firstOrNull { isDirectory(it) }
    }

    /**
     * Returns the manifest to compile against. If the module declares a
     * namespace in build.gradle but the manifest carries no package attribute,
     * a build-dir copy with the package attribute injected is produced so the
     * source tree is never modified.
     */
    private fun prepareManifest(original: Path, buildRoot: Path, namespace: String?): Path {
        if (namespace.isNullOrBlank()) return original
        val text = String(fileSystem.readAllBytes(original))
        val hasPackage = Regex("""package\s*=\s*["']""").containsMatchIn(text) ||
            Regex("""<manifest[^>]*\spackage\s*=\s*""").containsMatchIn(text)
        if (hasPackage) return original
        val injected = text.replace(
            Regex("""(<manifest\b[^>]*)(/?>)"""),
            "$1 package=\"${namespace.escapeXml()}\"$2"
        )
        val target = buildRoot.resolve("manifest/AndroidManifest.xml")
        fileSystem.createDirectories(target.parent)
        fileSystem.writeBytes(target, injected.toByteArray())
        return target
    }

    private fun String.escapeXml(): String =
        replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")

    private fun collectAllFlats(flatOut: Path, libFlatDirs: List<Path>): List<Path> =
        (fileSystem.walkFiles(flatOut, "*.flat") + libFlatDirs.flatMap { fileSystem.walkFiles(it, "*.flat") })
            .sorted()

    private fun collectSources(root: Path, language: String): Set<Path> {
        // Map language name to file extension (kotlin -> kt, java -> java)
        val extension = if (language == "kotlin") "kt" else language
        val candidates = listOf(
            root.resolve("src/main/java"),
            root.resolve("src/main/$language"),
            root.resolve("src/$language"),
            root.resolve(language)
        ).distinct()
        return candidates
            .filter { isDirectory(it) }
            .flatMap { fileSystem.walkFiles(it, "*.$extension") }
            .toSet()
    }

    private fun detectCompose(projectRoot: Path): Boolean {
        val candidates = listOf(
            projectRoot.resolve("app/build.gradle.kts"),
            projectRoot.resolve("app/build.gradle"),
            projectRoot.resolve("build.gradle.kts"),
            projectRoot.resolve("build.gradle")
        )
        for (file in candidates) {
            if (!fileSystem.exists(file)) continue
            val text = runCatching { String(fileSystem.readAllBytes(file)) }.getOrDefault("")

            // buildFeatures { compose = true } or compose = true
            if (Regex("""buildFeatures\s*\{[^}]*compose\s*=\s*true""", RegexOption.DOT_MATCHES_ALL).containsMatchIn(text)) return true
            if (Regex("""compose\s*=\s*true""").containsMatchIn(text)) return true
            // composeOptions block present
            if (Regex("""composeOptions\s*\{""").containsMatchIn(text)) return true
            // platform("androidx.compose:compose-bom:...") or compose-bom
            if (Regex("""compose-bom""").containsMatchIn(text)) return true
        }
        return false
    }

    private fun detectKotlinVersion(request: BuildRequest): String {
        // Try to read kotlin version from project build files
        val projectDir = Path.of(request.projectDir)
        val appDir = projectDir.resolve("app")
        val candidates = listOf(
            appDir.resolve("build.gradle.kts"),
            appDir.resolve("build.gradle"),
            projectDir.resolve("build.gradle.kts"),
            projectDir.resolve("build.gradle")
        )
        for (file in candidates) {
            if (!fileSystem.exists(file)) continue
            val text = runCatching { String(fileSystem.readAllBytes(file)) }.getOrDefault("")
            // kotlin = "2.2.10" or kotlin("2.2.10")
            val m = Regex("""kotlin\s*[=]\s*["']([^"']+)["']|kotlin\(["']([^"']+)["']\)""").find(text)
            if (m != null) return m.groupValues[1].ifBlank { m.groupValues[2] }
        }
        // Default to installed compiler version
        return "2.2.10"
    }

    private fun isDirectory(path: Path): Boolean {
        return fileSystem.exists(path) && fileSystem.metadata(path).isDirectory
    }

    private fun resolveBuildRoot(projectRoot: Path, config: EngineConfig): Path {
        val configOutput = config.buildOutputDir
        return if (configOutput != null) configOutput else projectRoot.resolve("build/hbe")
    }

    private fun applyOutputPath(apkPath: Path, request: BuildRequest): Path {
        val custom = request.outputApkPath ?: return apkPath
        val target = Path.of(custom).toAbsolutePath()
        fileSystem.createDirectories(target.parent)
        fileSystem.copy(apkPath, target)
        return target
    }

    private fun digestHex(bytes: ByteArray): String {
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private data class TaskSpec(
        val node: BuildNode,
        val params: Map<String, String>,
        val upstreamIds: List<String>,
        val inputFiles: () -> List<Path>,
        val outputDir: Path?,
        val run: () -> Unit,
        val restore: () -> Unit,
        val outputReady: () -> Boolean,
        val fingerprintOnly: Boolean = false
    )

    private object CacheArchive {
        fun zipDir(dir: Path, zipFile: Path) {
            Files.createDirectories(dir)
            Files.createDirectories(zipFile.parent)
            ZipOutputStream(BufferedOutputStream(Files.newOutputStream(zipFile))).use { zos ->
                Files.walk(dir).use { stream ->
                    stream.filter { Files.isRegularFile(it) }.forEach { file ->
                        val name = dir.relativize(file).toString().replace('\\', '/')
                        val entry = ZipEntry(name)
                        zos.putNextEntry(entry)
                        Files.newInputStream(file).use { it.transferTo(zos) }
                        zos.closeEntry()
                    }
                }
            }
        }

        fun unzipDir(zipFile: Path, dir: Path) {
            Files.createDirectories(dir)
            ZipFile(zipFile.toFile()).use { zf ->
                val entries = zf.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val target = dir.resolve(entry.name).normalize()
                    if (entry.isDirectory) {
                        Files.createDirectories(target)
                    } else {
                        Files.createDirectories(target.parent)
                        zf.getInputStream(entry).use { ins ->
                            Files.copy(ins, target, StandardCopyOption.REPLACE_EXISTING)
                        }
                    }
                }
            }
        }
    }
}
