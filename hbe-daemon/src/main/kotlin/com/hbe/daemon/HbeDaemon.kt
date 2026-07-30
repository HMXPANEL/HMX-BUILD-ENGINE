package com.hbe.daemon

import com.hbe.api.*
import com.hbe.api.dto.BuildRequest
import com.hbe.core.ConfigLoader
import com.hbe.core.DefaultHbeEngine
import com.hbe.core.DefaultLogger
import com.hbe.core.PhaseExecutor
import com.hbe.diagnostics.DiagnosticsImpl
import com.hbe.infra.JavaNetHttpClient
import com.hbe.infra.OsFileSystem
import com.hbe.infra.OsProcessRunner
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors

fun main(args: Array<String>) {
    HbeDaemon(args).start()
}

class HbeDaemon(private val args: Array<String>) {

    private val logger = DefaultLogger()
    private val fileSystem = OsFileSystem()
    private val processRunner = OsProcessRunner()
    private val configLoader = ConfigLoader(logger)
    private val phaseExecutor = PhaseExecutor(logger)
    private val diagnostics = DiagnosticsImpl(
        sdkManager = com.hbe.sdk.SdkManagerImpl(fileSystem, processRunner, logger),
        logger = logger
    )
    private val engine = DefaultHbeEngine(configLoader, phaseExecutor, diagnostics)

    private val port: Int = extractPort(args) ?: 8574
    private val pidFile: Path = Path.of(
        System.getProperty("user.home", "/tmp"), ".hbe", "daemon.pid"
    )
    private val threadPool = Executors.newCachedThreadPool()
    private var running = false

    fun start() {
        val subCommand = args.firstOrNull()

        when (subCommand) {
            "start" -> startDaemon()
            "stop" -> stopDaemon()
            "status" -> statusDaemon()
            "restart" -> {
                stopDaemon()
                startDaemon()
            }
            null -> startDaemon()  // default: start
            else -> {
                println("Unknown daemon command: $subCommand")
                println("Usage: hbe daemon <start|stop|status|restart>")
            }
        }
    }

    private fun startDaemon() {
        if (isDaemonRunning()) {
            println("[HBE] Daemon already running (PID: ${readPid()})")
            return
        }

        try {
            writePid()

            running = true
            logger.info("HBE daemon starting", mapOf("port" to port.toString()))

            val serverSocket = ServerSocket(port, 50, java.net.InetAddress.getByName("127.0.0.1"))
            println("[HBE] Daemon started on port $port (PID: ${readPid()})")

            Runtime.getRuntime().addShutdownHook(Thread {
                running = false
                cleanup()
            })

            while (running) {
                try {
                    val clientSocket = serverSocket.accept()
                    threadPool.submit { handleClient(clientSocket) }
                } catch (_: Exception) {
                    if (!running) break
                }
            }

            serverSocket.close()
        } catch (e: Exception) {
            logger.error("Daemon failed to start", mapOf("error" to (e.message ?: "")))
            cleanup()
            println("[HBE] Daemon failed to start: ${e.message}")
        }
    }

    private fun stopDaemon() {
        val pid = readPid()
        if (pid == null) {
            println("[HBE] Daemon is not running")
            return
        }

        running = false
        try {
            val process = Runtime.getRuntime().exec("kill $pid")
            process.waitFor()
            cleanup()
            println("[HBE] Daemon stopped")
        } catch (e: Exception) {
            println("[HBE] Failed to stop daemon: ${e.message}")
        }
    }

    private fun statusDaemon() {
        if (isDaemonRunning()) {
            println("[HBE] Daemon is running (PID: ${readPid()}, port: $port)")
        } else {
            println("[HBE] Daemon is not running")
        }
    }

    private fun handleClient(socket: Socket) {
        try {
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val writer = OutputStreamWriter(socket.getOutputStream())

            val requestLine = reader.readLine() ?: return
            val response = processRequest(requestLine)

            writer.write("$response\n")
            writer.flush()
        } catch (e: Exception) {
            logger.error("Error handling client", mapOf("error" to (e.message ?: "")))
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun processRequest(requestJson: String): String {
        // Simplified JSON-RPC handler — will be implemented with full protocol
        try {
            // Placeholder: parse method from JSON
            if (requestJson.contains("\"method\":\"build\"")) {
                val buildRequest = BuildRequest(
                    projectDir = ".",
                    variant = "debug"
                )
                val result = engine.build(buildRequest)
                return """{"jsonrpc":"2.0","result":{"status":"${result.status}"}}"""
            }

            if (requestJson.contains("\"method\":\"ping\"")) {
                return """{"jsonrpc":"2.0","result":{"status":"ok"}}"""
            }

            return """{"jsonrpc":"2.0","error":{"code":-32601,"message":"Method not found"}}"""
        } catch (e: Exception) {
            return """{"jsonrpc":"2.0","error":{"code":-32603,"message":"${e.message?.replace("\"", "\\\"") ?: "Internal error"}"}}"""
        }
    }

    private fun isDaemonRunning(): Boolean {
        val pid = readPid() ?: return false
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("kill", "-0", pid.toString()))
            process.waitFor() == 0
        } catch (_: Exception) {
            false
        }
    }

    private fun readPid(): Long? {
        return try {
            if (Files.exists(pidFile)) {
                Files.readString(pidFile).trim().toLongOrNull()
            } else null
        } catch (_: Exception) {
            null
        }
    }

    private fun writePid() {
        try {
            Files.createDirectories(pidFile.parent)
            Files.writeString(pidFile, ProcessHandle.current().pid().toString())
        } catch (_: Exception) {
            // PID file is optional
        }
    }

    private fun cleanup() {
        try { Files.deleteIfExists(pidFile) } catch (_: Exception) {}
    }

    private fun extractPort(args: Array<String>): Int? {
        for (i in args.indices) {
            if (args[i] == "--port" && i + 1 < args.size) {
                return args[i + 1].toIntOrNull()
            }
        }
        return null
    }
}
