package com.hbe.sdk

import com.hbe.api.*
import com.hbe.api.ToolError
import com.hbe.api.ToolErrorType
import com.hbe.api.ToolOptions
import com.hbe.api.ToolResult
import com.hbe.api.ToolRunner
import java.io.File
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class ToolRunnerImpl(
    private val sdkManager: SdkManager,
    private val processRunner: ProcessRunner,
    private val logger: Logger
) : ToolRunner {

    override fun run(tool: String, args: List<String>, options: ToolOptions): ToolResult {
        val toolPath = resolveToolPath(tool)
        if (toolPath == null) {
            return ToolResult(
                exitCode = -1, stdout = "", stderr = "",
                durationMs = 0, succeeded = false,
                error = ToolError(ToolErrorType.TOOL_NOT_FOUND, "Tool not found: $tool",
                    details = listOf("Ensure $tool is installed. Run 'hbe doctor' to check."))
            )
        }

        val token = options.cancellationToken
        if (token != null && token.isCancelled) {
            return ToolResult(
                exitCode = -1, stdout = "", stderr = "",
                durationMs = 0, succeeded = false,
                error = ToolError(ToolErrorType.CANCELLED, "Execution cancelled before starting")
            )
        }

        val processConfig = ProcessConfig(
            workingDir = options.workingDir,
            environment = options.environment,
            maxHeapMb = options.maxHeapMb,
            redirectErrorStream = !options.captureOutput
        )

        val startTime = System.currentTimeMillis()

        try {
            if (token != null) {
                return runWithCancellation(tool, toolPath, args, options, processConfig, token, startTime)
            }
            val result = processRunner.runWithTimeout(
                toolPath.toString(), args, options.timeoutMs, processConfig
            )
            return toToolResult(result, startTime, options)
        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - startTime
            val error = when {
                e.message?.contains("timeout", ignoreCase = true) == true ->
                    ToolError(ToolErrorType.TIMEOUT, "Tool execution timed out after ${options.timeoutMs}ms",
                        details = listOf("Tool: $tool", "Timeout: ${options.timeoutMs}ms", e.message ?: ""))
                else -> ToolError(ToolErrorType.EXECUTION_ERROR, e.message ?: "Unknown error",
                    details = listOf("Tool: $tool", e.message ?: ""))
            }
            return ToolResult(-1, "", "", duration, false, error)
        }
    }

    private fun runWithCancellation(
        toolName: String,
        toolPath: Path,
        args: List<String>,
        options: ToolOptions,
        processConfig: ProcessConfig,
        token: CancellationToken,
        startTime: Long
    ): ToolResult {
        val command = mutableListOf(toolPath.toString())
        command.addAll(args)

        val pb = ProcessBuilder(command)
        val workingDir = processConfig.workingDir
        if (workingDir != null) pb.directory(workingDir.toFile())
        if (processConfig.environment.isNotEmpty()) pb.environment().putAll(processConfig.environment)
        pb.redirectErrorStream(processConfig.redirectErrorStream)

        val stdoutB = StringBuilder()
        val stderrB = StringBuilder()

        val process = pb.start()
        val stdoutThread = captureStream(process.inputStream, stdoutB)
        val stderrThread = if (processConfig.redirectErrorStream) null
            else captureStream(process.errorStream, stderrB)

        stdoutThread.start()
        stderrThread?.start()

        val deadline = System.currentTimeMillis() + options.timeoutMs
        var timedOut = false
        var cancelled = false

        while (System.currentTimeMillis() < deadline) {
            if (token.isCancelled) {
                process.destroyForcibly()
                cancelled = true
                break
            }
            if (!process.isAlive) break
            try {
                process.waitFor(100, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }

        if (process.isAlive) {
            if (!cancelled) timedOut = true
            process.destroyForcibly()
            process.waitFor(3, TimeUnit.SECONDS)
        }

        stdoutThread.join(1000)
        stderrThread?.join(1000)

        val duration = System.currentTimeMillis() - startTime
        val exitCode = process.exitValue()
        val stdout = stdoutB.toString()
        val stderr = stderrB.toString()

        val error = when {
            cancelled -> ToolError(ToolErrorType.CANCELLED, "Tool execution cancelled by user",
                details = listOf("Tool: $toolName"))
            timedOut -> ToolError(ToolErrorType.TIMEOUT, "Tool execution timed out after ${options.timeoutMs}ms",
                details = listOf("Tool: $toolName", "Timeout: ${options.timeoutMs}ms"))
            exitCode != 0 -> ToolError(ToolErrorType.EXECUTION_ERROR, buildToolErrorMessage(toolName, exitCode, stderr),
                details = listOf("Tool: $toolName", "Exit code: $exitCode", stderr.lines().firstOrNull { it.isNotBlank() } ?: ""))
            else -> null
        }

        return ToolResult(exitCode, stdout, stderr, duration, exitCode == 0, error)
    }

    private fun resolveToolPath(tool: String): Path? {
        val known = mapOf(
            "aapt2" to "aapt2", "d8" to "d8", "zipalign" to "zipalign",
            "apksigner" to "apksigner", "adb" to "adb",
            "javac" to "javac", "java" to "java", "jar" to "jar",
            "kotlinc" to "kotlinc"
        )
        val mapped = known[tool] ?: tool
        return sdkManager.findTool(mapped)
            ?: processRunner.findTool(mapped)
    }

    private fun captureStream(input: java.io.InputStream, buffer: StringBuilder): Thread {
        return Thread {
            input.bufferedReader().use { reader ->
                reader.lines().forEach { line -> buffer.appendLine(line) }
            }
        }.also { it.isDaemon = true }
    }

    private fun toToolResult(result: ProcessResult, startTime: Long, options: ToolOptions): ToolResult {
        val succeeded = result.isSuccess
        val error = if (!succeeded) {
            val stderr = result.stderr
            ToolError(
                type = if (result.exitCode == -1) ToolErrorType.TIMEOUT else ToolErrorType.EXECUTION_ERROR,
                message = result.stderr.lines().firstOrNull { it.isNotBlank() } ?: "Tool exited with code ${result.exitCode}",
                details = listOf("Exit code: ${result.exitCode}")
            )
        } else null
        return ToolResult(
            result.exitCode,
            normalizeOutput(result.stdout),
            normalizeOutput(result.stderr),
            result.durationMs,
            succeeded,
            error
        )
    }

    private fun normalizeOutput(output: String): String {
        if (output.isEmpty() || output.endsWith("\n")) return output
        return output + "\n"
    }

    private fun buildToolErrorMessage(tool: String, exitCode: Int, stderr: String): String {
        val firstLine = stderr.lines().firstOrNull { it.isNotBlank() }
        return when {
            firstLine != null -> firstLine
            exitCode == 137 || exitCode == 143 -> "Process killed (OOM?)"
            else -> "$tool exited with code $exitCode"
        }
    }
}
