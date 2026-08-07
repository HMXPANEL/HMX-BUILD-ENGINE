package com.hbe.core.event

import com.hbe.api.Logger
import com.hbe.api.event.*

/**
 * Subscribes to the [BuildEventBus] and prints live build progress to the
 * console. It is a pure observer: every exception is caught internally so a
 * logging failure can never break the build.
 *
 * Output follows the contract:
 *   - Stage transitions print as [N/M] <Phase Name>
 *   - A heartbeat fires every second while any phase runs longer than 5s,
 *     showing elapsed time, memory and the file currently being processed.
 */
class BuildProgressTracker(
    private val logger: Logger
) : AutoCloseable {

    private enum class Stage(val label: String) {
        PROJECT_SCAN("Project Scan"),
        MANIFEST("Manifest Processing"),
        RESOURCE_MERGE("Resource Merge"),
        AAPT2_COMPILE("AAPT2 Compile"),
        AAPT2_LINK("AAPT2 Link"),
        JAVA_COMPILE("Java Compile"),
        KOTLIN_COMPILE("Kotlin Compile"),
        DEX_PACKAGING("D8 + Packaging"),
        ALIGN("Zip Align"),
        SIGNING("APK Signing"),
        FINALIZE("Finalize");

        companion object {
            fun fromPhase(phase: String): Stage? = when (phase) {
                "MANIFEST" -> MANIFEST
                "RESOURCE_MERGE" -> RESOURCE_MERGE
                "RESOURCE_COMPILE" -> AAPT2_COMPILE
                "RESOURCE_LINK" -> AAPT2_LINK
                "JAVA_COMPILE" -> JAVA_COMPILE
                "KOTLIN_COMPILE" -> KOTLIN_COMPILE
                "DEX", "PACKAGE" -> DEX_PACKAGING
                "ALIGN" -> ALIGN
                "SIGN" -> SIGNING
                else -> null
            }
        }
    }

    private data class PhaseState(
        val stage: Stage,
        var startMs: Long = System.currentTimeMillis(),
        var file: String? = null
    )

    private val phases = LinkedHashMap<String, PhaseState>()
    private var current: String? = null
    private var buildStartMs = 0L
    private var projectDir: String = ""
    private var variant: String = ""
    private var buildId: String = ""
    private var totalStages = Stage.entries.size
    private var heartbeatThread: Thread? = null
    private var closed = false

    /** Subscribe this tracker to the given event bus. Returns the close handle. */
    fun attach(bus: BuildEventBus): () -> Unit {
        val unsub = bus.subscribe { event -> onEvent(event) }
        startHeartbeat()
        return {
            unsub()
            close()
        }
    }

    override fun close() {
        closed = true
        heartbeatThread?.interrupt()
        heartbeatThread = null
    }

    private fun onEvent(event: BuildEvent) {
        try {
            when (event) {
                is BuildStartedEvent -> onBuildStarted(event)
                is PhaseStartedEvent -> onPhaseStarted(event.phase)
                is PhaseFinishedEvent -> onPhaseFinished(event.phase)
                is ResourceCompilationStartedEvent -> onPhaseStarted("RESOURCE_COMPILE")
                is ResourceCompilationFinishedEvent -> onPhaseFinished("RESOURCE_COMPILE")
                is ResourceLinkStartedEvent -> onPhaseStarted("RESOURCE_LINK")
                is ResourceLinkFinishedEvent -> onPhaseFinished("RESOURCE_LINK")
                is JavaCompilationStartedEvent -> onPhaseStarted("JAVA_COMPILE")
                is JavaCompilationFinishedEvent -> onPhaseFinished("JAVA_COMPILE")
                is KotlinCompilationStartedEvent -> onPhaseStarted("KOTLIN_COMPILE")
                is KotlinCompilationFinishedEvent -> onPhaseFinished("KOTLIN_COMPILE")
                is DexGenerationStartedEvent -> onPhaseStarted("DEX")
                is DexGenerationFinishedEvent -> onPhaseFinished("DEX")
                is PackagingStartedEvent -> onPhaseStarted("PACKAGE")
                is PackagingFinishedEvent -> onPhaseFinished("PACKAGE")
                is SigningStartedEvent -> onPhaseStarted("SIGN")
                is SigningFinishedEvent -> onPhaseFinished("SIGN")
                is ToolExecutionStartedEvent -> onToolStarted(event)
                is CacheHitEvent -> { /* counted, printed at end */ }
                is CacheMissEvent -> { /* counted, printed at end */ }
                is BuildFinishedEvent -> onBuildFinished()
                is BuildFailedEvent -> onBuildFailed()
                else -> {}
            }
        } catch (_: Exception) {
            // Never let logging break the build
        }
    }

    private fun onBuildStarted(event: BuildStartedEvent) {
        buildId = event.buildId
        buildStartMs = event.timestamp
        projectDir = event.metadata["projectDir"]?.toString() ?: ""
        variant = event.metadata["variant"]?.toString() ?: ""
        val javaVersion = System.getProperty("java.version")
        val os = System.getProperty("os.name")
        logger.info("Build started", mapOf(
            "buildId" to buildId,
            "project" to projectDir,
            "variant" to variant,
            "java" to javaVersion,
            "os" to os
        ))
        println()
        println("╔══════════════════════════════════════════════════════════════╗")
        println("║  HMX Build Engine                                            ║")
        println("╠══════════════════════════════════════════════════════════════╣")
        println("║  Project : ${projectDir.take(48).padEnd(48)} ║")
        println("║  Variant : ${variant.take(48).padEnd(48)} ║")
        println("║  Java    : ${javaVersion.take(48).padEnd(48)} ║")
        println("╚══════════════════════════════════════════════════════════════╝")
        println()
    }

    private fun onPhaseStarted(phase: String) {
        val stage = Stage.fromPhase(phase) ?: return
        val existing = phases[phase]
        if (existing == null) {
            phases[phase] = PhaseState(stage = stage)
        } else {
            existing.startMs = System.currentTimeMillis()
        }
        current = phase
        printStage(stage)
    }

    private fun onPhaseFinished(phase: String) {
        val state = phases[phase] ?: return
        val elapsed = System.currentTimeMillis() - state.startMs
        if (current == phase) current = null
        println("  ✓ ${state.stage.label} (${fmtDuration(elapsed)})")
    }

    private fun onToolStarted(event: ToolExecutionStartedEvent) {
        event.metadata["file"]?.let { currentFile ->
            phases[current]?.file = currentFile.toString()
        }
    }

    private fun printStage(stage: Stage) {
        val idx = stage.ordinal + 1
        totalStages = Stage.entries.size
        println("[$idx/$totalStages] ${stage.label}")
    }

    private fun onBuildFinished() {
        val total = System.currentTimeMillis() - buildStartMs
        println()
        println("────────────────────────────────────────────────────────────")
        println("Build complete in ${fmtDuration(total)}")
        println("────────────────────────────────────────────────────────────")
    }

    private fun onBuildFailed() {
        val total = System.currentTimeMillis() - buildStartMs
        println()
        println("────────────────────────────────────────────────────────────")
        println("Build failed after ${fmtDuration(total)}")
        println("────────────────────────────────────────────────────────────")
    }

    // --- Heartbeat ---------------------------------------------------------

    private fun startHeartbeat() {
        heartbeatThread = Thread({
            while (!closed && !Thread.currentThread().isInterrupted) {
                try {
                    Thread.sleep(1000)
                    maybePrintHeartbeat()
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }, "hbe-progress-heartbeat").apply {
            isDaemon = true
            start()
        }
    }

    private fun maybePrintHeartbeat() {
        val phase = current ?: return
        val state = phases[phase] ?: return
        val elapsed = System.currentTimeMillis() - state.startMs
        if (elapsed < HEARTBEAT_THRESHOLD_MS) return

        val mem = memoryString()
        val elapsedStr = fmtDuration(elapsed)
        val file = state.file?.let { "  file: ${it.take(60)}" } ?: ""
        println("  ⏳ Still running...  elapsed=$elapsedStr  mem=$mem$file")
    }

    private fun memoryString(): String {
        val rt = Runtime.getRuntime()
        val usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
        val maxMb = rt.maxMemory() / (1024 * 1024)
        return "${usedMb}MB/${maxMb}MB"
    }

    private fun fmtDuration(ms: Long): String {
        return when {
            ms < 1000 -> "${ms}ms"
            ms < 60_000 -> String.format("%.1fs", ms / 1000.0)
            else -> {
                val s = ms / 1000
                String.format("%dm%ds", s / 60, s % 60)
            }
        }
    }

    companion object {
        private const val HEARTBEAT_THRESHOLD_MS = 5_000L
    }
}
