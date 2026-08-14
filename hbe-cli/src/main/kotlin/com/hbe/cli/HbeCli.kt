package com.hbe.cli

import com.hbe.api.*
import com.hbe.api.dto.*
import com.hbe.core.ConfigLoader
import com.hbe.core.DefaultHbeEngine
import com.hbe.core.DefaultLogger
import com.hbe.core.PhaseExecutor
import com.hbe.core.project.ProjectImporter
import com.hbe.core.project.ProjectResolver
import com.hbe.diagnostics.DiagnosticsImpl
import com.hbe.infra.JavaNetHttpClient
import com.hbe.infra.OsFileSystem
import com.hbe.infra.OsProcessRunner
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    HbeCliRunner(args).run()
}

class HbeCliRunner(private val args: Array<String>) {

    private val logger = DefaultLogger()
    private val fileSystem = OsFileSystem()
    private val processRunner = OsProcessRunner()
    private val networkClient = JavaNetHttpClient()
    private val configLoader = ConfigLoader(logger)
    private val eventBus = com.hbe.core.event.InMemoryBuildEventBus()
    private val sdkManager = com.hbe.sdk.SdkManagerImpl(fileSystem, processRunner, logger, eventBus = eventBus)
    private val toolRunner = com.hbe.core.event.EventEmittingToolRunner(
        com.hbe.sdk.ToolRunnerImpl(sdkManager, processRunner, logger), eventBus
    )
    private val diagnostics = DiagnosticsImpl(
        sdkManager = sdkManager,
        logger = logger
    )
    private val pipeline = com.hbe.core.pipeline.DefaultBuildPipeline(
        sdkManager = sdkManager,
        resourceCompiler = com.hbe.resources.ResourceCompilerImpl(sdkManager, fileSystem, toolRunner, logger),
        sourceCompiler = com.hbe.compiler.SourceCompilerImpl(sdkManager, fileSystem, toolRunner, logger),
        dexEngine = com.hbe.dex.DexEngineImpl(sdkManager, fileSystem, toolRunner, logger),
        packager = com.hbe.packager.PackagerImpl(fileSystem, toolRunner, logger),
        signer = com.hbe.signer.SignerImpl(fileSystem, toolRunner, logger),
        toolRunner = toolRunner,
        fileSystem = fileSystem,
        eventBus = eventBus,
        logger = logger
    )
    private val phaseExecutor = PhaseExecutor(logger, pipeline)
    private val engine = DefaultHbeEngine(configLoader, phaseExecutor, diagnostics)

    private val dependencyManager = com.hbe.dependency.DependencyManagerImpl(
        fileSystem, networkClient,
        com.hbe.cache.CacheManagerImpl(fileSystem, java.nio.file.Path.of(System.getProperty("user.home") ?: ".", ".hbe", "cache")),
        logger
    )
    private val projectImporter = ProjectImporter(fileSystem, logger)
    private val projectResolver = ProjectResolver(dependencyManager, logger)

    private fun buildIncrementalPipeline(deps: com.hbe.core.pipeline.ProjectDependencies?): com.hbe.core.pipeline.IncrementalBuildPipeline {
        val eventBus = com.hbe.core.event.InMemoryBuildEventBus()
        return com.hbe.core.pipeline.IncrementalBuildPipeline(
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
            cacheManager = com.hbe.cache.CacheManagerImpl(fileSystem, java.nio.file.Path.of(System.getProperty("user.home") ?: ".", ".hbe", "cache")),
            scheduler = com.hbe.scheduler.TaskScheduler(com.hbe.memory.MemoryManagerImpl(logger), logger),
            memoryMonitor = com.hbe.memory.MemoryManagerImpl(logger),
            projectDependencies = deps
        )
    }

    fun run() {
        if (args.isEmpty()) {
            printUsage()
            exitProcess(2)
        }

        val command = args[0]
        val commandArgs = args.drop(1).toTypedArray()

        try {
            when (command) {
                "build" -> cmdBuild(commandArgs)
                "clean" -> cmdClean(commandArgs)
                "doctor" -> cmdDoctor(commandArgs)
                "install" -> cmdInstall(commandArgs)
                "prepare" -> cmdPrepare(commandArgs)
                "analyze" -> cmdAnalyze(commandArgs)
                "import" -> cmdImport(commandArgs)
                "cache" -> cmdCache(commandArgs)
                "daemon" -> cmdDaemon(commandArgs)
                "version" -> cmdVersion()
                "help" -> printUsage()
                else -> {
                    println("[HBE] Unknown command: $command")
                    printUsage()
                    exitProcess(2)
                }
            }
        } catch (e: Exception) {
            logger.error("Command failed", mapOf("command" to command, "error" to (e.message ?: "")))
            exitProcess(1)
        }
    }

    private fun buildMultiModuleBuilder(): com.hbe.core.pipeline.MultiModuleBuilder {
        return com.hbe.core.pipeline.MultiModuleBuilder(
            sdkManager = sdkManager,
            resourceCompiler = com.hbe.resources.ResourceCompilerImpl(sdkManager, fileSystem, toolRunner, logger),
            sourceCompiler = com.hbe.compiler.SourceCompilerImpl(sdkManager, fileSystem, toolRunner, logger),
            dexEngine = com.hbe.dex.DexEngineImpl(sdkManager, fileSystem, toolRunner, logger),
            packager = com.hbe.packager.PackagerImpl(fileSystem, toolRunner, logger),
            signer = com.hbe.signer.SignerImpl(fileSystem, toolRunner, logger),
            toolRunner = toolRunner,
            fileSystem = fileSystem,
            logger = logger,
            cacheManager = com.hbe.cache.CacheManagerImpl(fileSystem, java.nio.file.Path.of(System.getProperty("user.home") ?: ".", ".hbe", "cache")),
            scheduler = com.hbe.scheduler.TaskScheduler(com.hbe.memory.MemoryManagerImpl(logger), logger),
            memoryMonitor = com.hbe.memory.MemoryManagerImpl(logger),
            resolver = projectResolver
        )
    }

    private fun buildAppBundleBuilder(): com.hbe.core.pipeline.AppBundleBuilder {
        return com.hbe.core.pipeline.AppBundleBuilder(
            sdkManager = sdkManager,
            resourceCompiler = com.hbe.resources.ResourceCompilerImpl(sdkManager, fileSystem, toolRunner, logger),
            sourceCompiler = com.hbe.compiler.SourceCompilerImpl(sdkManager, fileSystem, toolRunner, logger),
            dexEngine = com.hbe.dex.DexEngineImpl(sdkManager, fileSystem, toolRunner, logger),
            packager = com.hbe.packager.PackagerImpl(fileSystem, toolRunner, logger),
            toolRunner = toolRunner,
            fileSystem = fileSystem,
            logger = logger,
            resolver = projectResolver
        )
    }

    private fun cmdBuild(args: Array<String>) {
        val projectDir = args.firstOrNull() ?: "."
        val variant = extractOption(args, "--variant", "-v") ?: "debug"
        val clean = args.contains("--clean") || args.contains("-c")
        val compose = args.contains("--compose")
        val json = args.contains("--json")
        val withDeps = args.contains("--deps")
        val aab = args.contains("--aab")
        val ramBudget = extractOption(args, "--ram-budget")?.toIntOrNull() ?: 1024

        println("[HBE] Building $projectDir ($variant)${if (aab) " as App Bundle" else ""}...")

        val request = BuildRequest(
            projectDir = projectDir,
            variant = variant,
            clean = clean,
            compose = compose,
            ramBudgetMb = ramBudget,
            incremental = !clean,
            format = if (aab) "aab" else "apk"
        )

        val model = runCatching { projectImporter.importProject(java.nio.file.Path.of(projectDir)) }.getOrNull()
        val result = if (aab) {
            val m = model ?: projectImporter.importProject(java.nio.file.Path.of(projectDir))
            buildAppBundleBuilder().build(
                m,
                request,
                com.hbe.api.EngineConfig()
            )
        } else if (model != null && model.modules.size > 1) {
            println("[HBE] Multi-module project detected (${model.modules.size} modules), building in order: ${model.moduleOrder.joinToString(", ") { it.path }}")
            buildMultiModuleBuilder().build(
                model,
                request,
                com.hbe.api.EngineConfig()
            )
        } else if (withDeps) {
            val deps = tryResolveDependencies(projectDir)
            buildIncrementalPipeline(deps).execute(
                com.hbe.core.BuildContextImpl(
                    buildId = "bld-" + java.util.UUID.randomUUID().toString().substring(0, 8),
                    request = request,
                    config = com.hbe.api.EngineConfig()
                )
            )
        } else {
            engine.build(request)
        }

        if (json) {
            println("""{"status":"${result.status}","apkPath":"${result.apkPath ?: ""}","aabPath":"${result.aabPath ?: ""}","buildId":"${result.buildId}"}""")
        } else {
            printBuildResult(result)
        }

        exitProcess(if (result.status == BuildResult.Status.SUCCESS) 0 else 1)
    }

    private fun tryResolveDependencies(projectDir: String): com.hbe.core.pipeline.ProjectDependencies? {
        return try {
            val model = projectImporter.importProject(java.nio.file.Path.of(projectDir))
            val app = model.applicationModule ?: model.modules.firstOrNull()
                ?: return null
            println("[HBE] Imported project '${model.name}', resolving ${app.dependencies.size} dependency declarations...")
            projectResolver.resolve(model, app.path)
        } catch (e: Exception) {
            println("[HBE] Dependency resolution failed: ${e.message}")
            null
        }
    }

    private fun cmdImport(args: Array<String>) {
        val projectDir = args.firstOrNull() ?: "."
        val model = projectImporter.importProject(java.nio.file.Path.of(projectDir))
        println(projectImporter.describe(model))

        val app = model.applicationModule ?: model.modules.firstOrNull()
        if (app != null && app.dependencies.isNotEmpty()) {
            println("Resolved dependencies:")
            val deps = projectResolver.resolve(model, app.path)
            println("  classpath entries : ${deps.classpath.size}")
            deps.classpath.forEach { println("    $it") }
            println("  library res dirs  : ${deps.libraryResDirs.size}")
            deps.libraryResDirs.forEach { println("    $it") }
            println("  library assets    : ${deps.libraryAssets.size}")
            deps.libraryAssets.forEach { println("    $it") }
            println("  native libs       : ${deps.nativeLibs.size}")
            deps.nativeLibs.forEach { println("    $it") }
        }
    }

    private fun cmdClean(args: Array<String>) {
        val projectDir = args.firstOrNull() ?: "."
        val cleanAll = args.contains("--all")
        val result = engine.clean(CleanRequest(projectDir, cleanAll))
        println("[HBE] ${result.message}")
    }

    private fun cmdDoctor(args: Array<String>) {
        val json = args.contains("--json")
        val report = engine.doctor(DoctorRequest(json = json))

        if (json) {
            println("{\"status\":\"${report.status}\",\"checks\":{}}")
        } else {
            println("[HBE] Health Check Report")
            println("Status: ${report.status}")
            for ((name, check) in report.checks) {
                println("  $name: [${check.status}] ${check.message}")
            }
            for (rec in report.recommendations) {
                println("  Recommendation: $rec")
            }
        }
    }

    private fun cmdInstall(args: Array<String>) {
        val apkPath = args.firstOrNull()
        if (apkPath == null) {
            println("[HBE] Usage: hbe install <apk-path> [--device <id>]")
            exitProcess(2)
        }
        val deviceId = extractOption(args, "--device", "-d")
        val result = engine.install(InstallRequest(apkPath, deviceId))
        println("[HBE] ${result.message}")
    }

    private fun cmdPrepare(args: Array<String>) {
        println("[HBE] Prepare not yet implemented")
    }

    private fun cmdAnalyze(args: Array<String>) {
        val projectDir = args.firstOrNull() ?: "."
        val result = engine.analyze(AnalyzeRequest(projectDir))
        println("[HBE] Project analysis not yet implemented")
    }

    private fun cmdCache(args: Array<String>) {
        val subCommand = args.firstOrNull() ?: "stats"
        val command = when (subCommand) {
            "stats" -> CacheCommand.STATS
            "prune" -> CacheCommand.PRUNE
            "clear" -> CacheCommand.CLEAR
            else -> {
                println("[HBE] Unknown cache command: $subCommand")
                println("Usage: hbe cache <stats|prune|clear>")
                exitProcess(2)
                return
            }
        }
        engine.cache(CacheRequest(command))
    }

    private fun cmdDaemon(args: Array<String>) {
        println("[HBE] Daemon mode not yet implemented")
    }

    private fun cmdVersion() {
        println("HBE Build Engine v1.0.0-SNAPSHOT")
        println("HMX Build Engine — Android APK build tool")
    }

    private fun printBuildResult(result: BuildResult) {
        when (result.status) {
            BuildResult.Status.SUCCESS -> {
                val outPath = result.apkPath ?: result.aabPath ?: "unknown"
                val label = if (result.aabPath != null && result.apkPath == null) "App Bundle" else "Build"
                println("[HBE] $label complete: $outPath")
                if (result.apkSizeBytes > 0) {
                    println("Size: ${result.apkSizeBytes / 1024} KB | Duration: ${result.totalDurationMs / 1000}s")
                }
                println("Cache hits: ${result.cacheHits} | Misses: ${result.cacheMisses}")
            }
            BuildResult.Status.FAILURE -> {
                val error = result.error
                if (error != null) {
                    println("[HBE] Build failed: ${error.message}")
                    if (error.suggestion != null) println("Suggestion: ${error.suggestion}")
                    if (error.details.isNotEmpty()) {
                        val shown = error.details.take(50)
                        shown.forEach { println("  $it") }
                        if (error.details.size > 50) println("  ... and ${error.details.size - 50} more")
                    }
                } else {
                    println("[HBE] Build failed with unknown error")
                }
            }
            BuildResult.Status.CANCELLED -> {
                println("[HBE] Build cancelled")
            }
        }
    }

    private fun printUsage() {
        println("HBE Build Engine — HMX Android APK build tool")
        println()
        println("Usage: hbe <command> [options] [path]")
        println()
        println("Commands:")
        println("  build       Build APK (default)")
        println("  clean       Clean build artifacts")
        println("  doctor      Run system diagnostics")
        println("  install     Install APK via ADB")
        println("  prepare     Pre-download SDK + dependencies")
        println("  analyze     Analyze project structure")
        println("  import      Import project + resolve dependencies")
        println("  cache       Manage build cache")
        println("  daemon      Start/stop daemon")
        println("  version     Print version")
        println("  help        Print this help")
        println()
        println("Options:")
        println("  --variant, -v <name>    Build variant (debug/release)")
        println("  --clean, -c             Clean before building")
        println("  --compose               Enable Compose compiler plugin")
        println("  --deps                  Resolve project dependencies before building")
        println("  --ram-budget <mb>       Max RAM in MB")
        println("  --json                  Output as JSON")
        println("  --device, -d <id>       Device serial for install")
    }

    private fun extractOption(args: Array<String>, vararg names: String): String? {
        for (i in args.indices) {
            if (args[i] in names && i + 1 < args.size) {
                return args[i + 1]
            }
        }
        return null
    }
}
