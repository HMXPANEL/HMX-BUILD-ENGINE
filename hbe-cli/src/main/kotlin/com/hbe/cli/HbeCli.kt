package com.hbe.cli

import com.hbe.api.*
import com.hbe.api.dto.*
import com.hbe.core.ConfigLoader
import com.hbe.core.DefaultHbeEngine
import com.hbe.core.DefaultLogger
import com.hbe.core.PhaseExecutor
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
    private val phaseExecutor = PhaseExecutor(logger)
    private val diagnostics = DiagnosticsImpl(
        sdkManager = com.hbe.sdk.SdkManagerImpl(fileSystem, processRunner, logger),
        logger = logger
    )
    private val engine = DefaultHbeEngine(configLoader, phaseExecutor, diagnostics)

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

    private fun cmdBuild(args: Array<String>) {
        val projectDir = args.firstOrNull() ?: "."
        val variant = extractOption(args, "--variant", "-v") ?: "debug"
        val clean = args.contains("--clean") || args.contains("-c")
        val compose = args.contains("--compose")
        val json = args.contains("--json")
        val ramBudget = extractOption(args, "--ram-budget")?.toIntOrNull() ?: 1024

        println("[HBE] Building $projectDir ($variant)...")

        val request = BuildRequest(
            projectDir = projectDir,
            variant = variant,
            clean = clean,
            compose = compose,
            ramBudgetMb = ramBudget,
            incremental = !clean
        )

        val result = engine.build(request)

        if (json) {
            println("""{"status":"${result.status}","apkPath":"${result.apkPath ?: ""}","buildId":"${result.buildId}"}""")
        } else {
            printBuildResult(result)
        }

        exitProcess(if (result.status == BuildResult.Status.SUCCESS) 0 else 1)
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
                println("[HBE] Build complete: ${result.apkPath ?: "unknown"}")
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
        println("  cache       Manage build cache")
        println("  daemon      Start/stop daemon")
        println("  version     Print version")
        println("  help        Print this help")
        println()
        println("Options:")
        println("  --variant, -v <name>    Build variant (debug/release)")
        println("  --clean, -c             Clean before building")
        println("  --compose               Enable Compose compiler plugin")
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
