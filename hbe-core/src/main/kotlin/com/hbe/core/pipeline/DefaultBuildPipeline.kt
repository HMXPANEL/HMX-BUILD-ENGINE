package com.hbe.core.pipeline

import com.hbe.api.*
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
import java.nio.file.Path

class DefaultBuildPipeline(
    private val sdkManager: SdkManager,
    private val resourceCompiler: ResourceCompiler,
    private val sourceCompiler: SourceCompiler,
    private val dexEngine: DexEngine,
    private val packager: Packager,
    private val signer: Signer,
    private val toolRunner: ToolRunner,
    private val fileSystem: FileSystem,
    private val eventBus: BuildEventBus,
    private val logger: Logger
) : BuildPipeline {

    private val progressTracker = BuildProgressTracker(logger).also {
        it.attach(eventBus)
    }

    override fun execute(context: BuildContext): BuildResult {
        val buildId = context.buildId
        val request = context.request
        val config = context.config
        val token = context.cancellationToken
        val timings = mutableListOf<PhaseTiming>()
        val buildStart = System.currentTimeMillis()
        var currentPhase = "BUILD"

        if (toolRunner is EventEmittingToolRunner) toolRunner.buildId = buildId

        eventBus.publish(BuildStartedEvent(
            buildId = buildId,
            metadata = mapOf("projectDir" to request.projectDir, "variant" to request.variant)
        ))

        try {
            token.throwIfCancelled()

            val projectRoot = findProjectRoot(Path.of(request.projectDir))
            val manifest = findManifest(projectRoot)
                ?: throw BuildException(
                    message = "AndroidManifest.xml not found in $projectRoot",
                    errorCode = "MANIFEST_NOT_FOUND",
                    suggestion = "Ensure the project contains an AndroidManifest.xml"
                )
            val resDir = findResDir(projectRoot)
            val compileSdk = request.compileSdk ?: resolveDefaultCompileSdk()
            val minSdk = request.minSdk ?: 24
            val variant = request.variant

            val resolution = resolveSdk(request, config, compileSdk)
            val androidJar = resolution.androidJar
            val buildRoot = resolveBuildRoot(projectRoot, config)
            fileSystem.createDirectories(buildRoot)

            // 1. RESOURCE pipeline
            currentPhase = "RESOURCE_COMPILE"
            val flatOut = buildRoot.resolve("res/flat")
            val linkOut = buildRoot.resolve("res/link")
            var flatFiles: List<Path> = emptyList()
            if (resDir != null) {
                eventBus.publish(ResourceCompilationStartedEvent(buildId))
                val (files, duration) = timed { resourceCompiler.compile(resDir, flatOut) }
                flatFiles = files
                eventBus.publish(ResourceCompilationFinishedEvent(buildId,
                    durationMs = duration, metadata = mapOf("flatFileCount" to flatFiles.size)))
                timings.add(PhaseTiming("RESOURCE_COMPILE", PhaseTiming.PhaseStatus.SUCCESS,
                    duration, inputCount = flatFiles.size))
            } else {
                eventBus.publish(BuildWarningEvent(buildId, phase = currentPhase,
                    metadata = mapOf("message" to "No res/ directory — linking manifest only")))
            }

            currentPhase = "RESOURCE_LINK"
            eventBus.publish(ResourceLinkStartedEvent(buildId))
            val (bundle, linkDuration) = timed {
                resourceCompiler.link(flatFiles, manifest, linkOut, compileSdk)
            }
            eventBus.publish(ResourceLinkFinishedEvent(buildId,
                durationMs = linkDuration, metadata = mapOf("configurations" to bundle.configurations.size)))
            timings.add(PhaseTiming("RESOURCE_LINK", PhaseTiming.PhaseStatus.SUCCESS,
                linkDuration, inputCount = bundle.resourceIds.size))

            // 2. SOURCE compilation
            val javaSources = collectSources(projectRoot, "java") + setOf(bundle.rJava)
            val kotlinSources = collectSources(projectRoot, "kotlin")
            val classesOut = buildRoot.resolve("classes")
            val baseClasspath = Classpath(entries = listOf(androidJar))

            val classFiles = mutableSetOf<Path>()
            if (javaSources.isNotEmpty()) {
                currentPhase = "JAVA_COMPILE"
                eventBus.publish(JavaCompilationStartedEvent(buildId,
                    metadata = mapOf("sourceCount" to javaSources.size)))
                val (compiled, javaDuration) = timed {
                    sourceCompiler.compileJava(javaSources, baseClasspath, classesOut)
                }
                classFiles.addAll(compiled)
                eventBus.publish(JavaCompilationFinishedEvent(buildId,
                    durationMs = javaDuration, metadata = mapOf("classFileCount" to compiled.size)))
                timings.add(PhaseTiming("JAVA_COMPILE", PhaseTiming.PhaseStatus.SUCCESS,
                    javaDuration, inputCount = javaSources.size))
            }

            if (kotlinSources.isNotEmpty()) {
                currentPhase = "KOTLIN_COMPILE"
                eventBus.publish(KotlinCompilationStartedEvent(buildId,
                    metadata = mapOf("sourceCount" to kotlinSources.size)))
                val kotlinClasspath = baseClasspath.addOutputDir(classesOut)
                val (compiled, kotlinDuration) = timed {
                    sourceCompiler.compileKotlin(kotlinSources, kotlinClasspath, classesOut,
                        request.compose)
                }
                classFiles.addAll(compiled)
                eventBus.publish(KotlinCompilationFinishedEvent(buildId,
                    durationMs = kotlinDuration, metadata = mapOf("classFileCount" to compiled.size)))
                timings.add(PhaseTiming("KOTLIN_COMPILE", PhaseTiming.PhaseStatus.SUCCESS,
                    kotlinDuration, inputCount = kotlinSources.size))
            }

            if (classFiles.isEmpty()) {
                throw BuildException(
                    message = "No Java or Kotlin sources found",
                    errorCode = "NO_SOURCES",
                    suggestion = "Add sources under src/main/java or src/main/kotlin"
                )
            }

            // 3. DEX generation
            currentPhase = "DEX"
            eventBus.publish(DexGenerationStartedEvent(buildId))
            val dexOut = buildRoot.resolve("dex")
            val (dexOutput, dexDuration) = timed {
                dexEngine.dex(classFiles, DexConfig(
                    minSdk = minSdk,
                    debug = variant != "release",
                    outputDir = dexOut,
                    libraryJars = listOf(androidJar),
                    isRelease = variant == "release"
                ))
            }
            eventBus.publish(DexGenerationFinishedEvent(buildId,
                durationMs = dexDuration,
                metadata = mapOf("dexFileCount" to dexOutput.dexFileCount,
                    "methodCount" to dexOutput.totalMethodCount)))
            timings.add(PhaseTiming("DEX", PhaseTiming.PhaseStatus.SUCCESS,
                dexDuration, inputCount = classFiles.size))

            // 4. PACKAGE
            currentPhase = "PACKAGE"
            eventBus.publish(PackagingStartedEvent(buildId))
            val (apkFile, packageDuration) = timed {
                packager.packageApk(dexOutput, bundle, bundle.manifest, outputDir = buildRoot)
            }
            eventBus.publish(PackagingFinishedEvent(buildId,
                durationMs = packageDuration, metadata = mapOf("apkSizeBytes" to fileSystem.size(apkFile))))
            timings.add(PhaseTiming("PACKAGE", PhaseTiming.PhaseStatus.SUCCESS, packageDuration))

            // 5. ALIGN
            currentPhase = "ALIGN"
            eventBus.publish(PhaseStartedEvent(buildId, phase = "ALIGN"))
            val (alignedApk, alignDuration) = timed { packager.zipalign(apkFile) }
            eventBus.publish(PhaseFinishedEvent(buildId, phase = "ALIGN", durationMs = alignDuration))
            timings.add(PhaseTiming("ALIGN", PhaseTiming.PhaseStatus.SUCCESS, alignDuration))

            // 6. SIGN
            currentPhase = "SIGN"
            val signingConfig = request.signingConfig
                ?: if (variant == "debug") SigningConfig.debug() else SigningConfig.unsigned()
            eventBus.publish(SigningStartedEvent(buildId,
                metadata = mapOf("type" to signingConfig.type.name)))
            val (signedApk, signDuration) = timed { signer.sign(alignedApk, signingConfig) }
            eventBus.publish(SigningFinishedEvent(buildId,
                durationMs = signDuration, metadata = mapOf("type" to signingConfig.type.name)))
            timings.add(PhaseTiming("SIGN", PhaseTiming.PhaseStatus.SUCCESS, signDuration))

            val finalApk = applyOutputPath(signedApk.apkPath, request)
            val totalDuration = System.currentTimeMillis() - buildStart

            eventBus.publish(BuildFinishedEvent(buildId, durationMs = totalDuration,
                metadata = mapOf("apkPath" to finalApk.toString(), "phases" to timings.size)))

            return BuildResult(
                status = BuildResult.Status.SUCCESS,
                apkPath = finalApk.toString(),
                apkSizeBytes = fileSystem.size(finalApk),
                phases = timings,
                totalDurationMs = totalDuration,
                buildId = buildId,
                metadata = mapOf("androidJar" to androidJar.toString())
            )
        } catch (e: BuildCancelledException) {
            val duration = System.currentTimeMillis() - buildStart
            eventBus.publish(BuildCancelledEvent(buildId, durationMs = duration))
            timings.add(PhaseTiming(currentPhase, PhaseTiming.PhaseStatus.CANCELLED))
            return BuildResult(
                status = BuildResult.Status.FAILURE,
                error = BuildError(currentPhase, "CANCELLED", "Build was cancelled"),
                phases = timings,
                totalDurationMs = duration,
                buildId = buildId
            )
        } catch (e: BuildException) {
            val duration = System.currentTimeMillis() - buildStart
            eventBus.publish(BuildErrorEvent(buildId, phase = currentPhase,
                metadata = mapOf("code" to e.errorCode, "message" to e.message)))
            timings.add(PhaseTiming(currentPhase, PhaseTiming.PhaseStatus.FAILED))
            return BuildResult(
                status = BuildResult.Status.FAILURE,
                error = BuildError(currentPhase, e.errorCode, e.message ?: "Unknown build error",
                    e.suggestion, e.details),
                phases = timings,
                totalDurationMs = duration,
                buildId = buildId
            )
        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - buildStart
            eventBus.publish(BuildErrorEvent(buildId, phase = currentPhase,
                metadata = mapOf("code" to "INTERNAL_ERROR", "message" to (e.message ?: ""))))
            timings.add(PhaseTiming(currentPhase, PhaseTiming.PhaseStatus.FAILED))
            return BuildResult(
                status = BuildResult.Status.FAILURE,
                error = BuildError(currentPhase, "INTERNAL_ERROR", e.message ?: "Unknown build error"),
                phases = timings,
                totalDurationMs = duration,
                buildId = buildId
            )
        }
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

    private fun collectSources(root: Path, language: String): Set<Path> {
        val candidates = listOf(
            root.resolve("src/main/$language"),
            root.resolve("src/$language"),
            root.resolve(language)
        )
        return candidates
            .filter { isDirectory(it) }
            .flatMap { fileSystem.walkFiles(it, "*.$language") }
            .toSet()
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

    private inline fun <T> timed(block: () -> T): Pair<T, Long> {
        val start = System.currentTimeMillis()
        val value = block()
        return value to (System.currentTimeMillis() - start)
    }
}
