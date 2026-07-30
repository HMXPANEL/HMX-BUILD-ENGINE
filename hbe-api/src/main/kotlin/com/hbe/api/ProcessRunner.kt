package com.hbe.api

import java.nio.file.Path

interface ProcessRunner {
    fun run(tool: String, args: List<String>, config: ProcessConfig = ProcessConfig()): ProcessResult
    fun runWithTimeout(tool: String, args: List<String>, timeoutMs: Long, config: ProcessConfig = ProcessConfig()): ProcessResult
    fun isToolAvailable(tool: String): Boolean
    fun findTool(name: String): Path?
}

data class ProcessConfig(
    val workingDir: Path? = null,
    val environment: Map<String, String> = emptyMap(),
    val maxHeapMb: Int = 256,
    val redirectErrorStream: Boolean = true
)

data class ProcessResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val durationMs: Long
) {
    val isSuccess: Boolean get() = exitCode == 0
    val isFailure: Boolean get() = exitCode != 0
}
