package com.hbe.api

import java.nio.file.Path

interface ToolRunner {
    fun run(
        tool: String,
        args: List<String>,
        options: ToolOptions = ToolOptions()
    ): ToolResult
}

data class ToolOptions(
    val workingDir: Path? = null,
    val environment: Map<String, String> = emptyMap(),
    val timeoutMs: Long = 300_000,
    val maxHeapMb: Int = 256,
    val captureOutput: Boolean = true,
    val cancellationToken: CancellationToken? = null
)

data class ToolResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val durationMs: Long,
    val succeeded: Boolean,
    val error: ToolError? = null
)

data class ToolError(
    val type: ToolErrorType,
    val message: String,
    val details: List<String> = emptyList()
)

enum class ToolErrorType {
    TOOL_NOT_FOUND,
    TIMEOUT,
    CANCELLED,
    EXECUTION_ERROR,
    UNKNOWN
}
