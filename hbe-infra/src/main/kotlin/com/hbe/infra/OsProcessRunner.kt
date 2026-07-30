package com.hbe.infra

import com.hbe.api.ProcessConfig
import com.hbe.api.ProcessResult
import com.hbe.api.ProcessRunner
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class OsProcessRunner : ProcessRunner {

    override fun run(tool: String, args: List<String>, config: ProcessConfig): ProcessResult {
        return runWithTimeout(tool, args, 300_000, config)
    }

    override fun runWithTimeout(
        tool: String,
        args: List<String>,
        timeoutMs: Long,
        config: ProcessConfig
    ): ProcessResult {
        val toolPath = findTool(tool) ?: throw IllegalStateException("Tool not found: $tool")

        val command = mutableListOf(toolPath.toString())
        command.addAll(args)

        val startTime = System.currentTimeMillis()

        val processBuilder = ProcessBuilder(command)
            .redirectErrorStream(config.redirectErrorStream)

        val workingDir = config.workingDir
        if (workingDir != null) {
            processBuilder.directory(workingDir.toFile())
        }

        if (config.environment.isNotEmpty()) {
            processBuilder.environment().putAll(config.environment)
        }

        val stdout = StringBuilder()
        val stderr = StringBuilder()

        try {
            val process = processBuilder.start()

            val stdoutThread = Thread {
                process.inputStream.bufferedReader().use { reader ->
                    reader.lines().forEach { line ->
                        stdout.appendLine(line)
                    }
                }
            }
            stdoutThread.isDaemon = true
            stdoutThread.start()

            val stderrThread = Thread {
                process.errorStream.bufferedReader().use { reader ->
                    reader.lines().forEach { line ->
                        stderr.appendLine(line)
                    }
                }
            }
            stderrThread.isDaemon = true
            stderrThread.start()

            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            stdoutThread.join(1000)
            stderrThread.join(1000)

            val duration = System.currentTimeMillis() - startTime

            if (!finished) {
                process.destroyForcibly()
                return ProcessResult(
                    exitCode = -1,
                    stdout = stdout.toString(),
                    stderr = "Process timed out after ${timeoutMs}ms\n${stderr}",
                    durationMs = duration
                )
            }

            return ProcessResult(
                exitCode = process.exitValue(),
                stdout = stdout.toString(),
                stderr = stderr.toString(),
                durationMs = duration
            )
        } catch (e: Exception) {
            return ProcessResult(
                exitCode = -1,
                stdout = stdout.toString(),
                stderr = stderr.toString() + "\n${e.message}",
                durationMs = System.currentTimeMillis() - startTime
            )
        }
    }

    override fun isToolAvailable(tool: String): Boolean {
        return findTool(tool) != null
    }

    override fun findTool(name: String): Path? {
        // Search PATH
        val pathEnv = System.getenv("PATH") ?: return null
        val pathDirs = pathEnv.split(File.pathSeparator)

        for (dir in pathDirs) {
            val toolPath = Path.of(dir, name)
            if (Files.isExecutable(toolPath)) return toolPath.toAbsolutePath()
            // On Windows, try with .exe extension
            val toolPathExe = Path.of(dir, "$name.exe")
            if (Files.isExecutable(toolPathExe)) return toolPathExe.toAbsolutePath()
        }

        return null
    }
}
