package com.hbe.core

import com.hbe.api.Logger
import java.io.PrintStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class DefaultLogger(
    private val minLevel: Logger.LogLevel = Logger.LogLevel.INFO,
    private val output: PrintStream = System.out,
    private val buildId: String? = null
) : Logger {

    private val outputs = mutableListOf<Logger.LogOutput>()
    private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
        .withZone(ZoneId.of("UTC"))

    init {
        outputs.add(ConsoleOutput(output))
    }

    override fun debug(message: String, context: Map<String, Any>) {
        log(Logger.LogLevel.DEBUG, message, context)
    }

    override fun info(message: String, context: Map<String, Any>) {
        log(Logger.LogLevel.INFO, message, context)
    }

    override fun warn(message: String, context: Map<String, Any>) {
        log(Logger.LogLevel.WARN, message, context)
    }

    override fun error(message: String, context: Map<String, Any>) {
        log(Logger.LogLevel.ERROR, message, context)
    }

    override fun trace(message: String, context: Map<String, Any>) {
        log(Logger.LogLevel.TRACE, message, context)
    }

    override fun setLevel(level: Logger.LogLevel) {
        // Level change handled externally; this logger recreated with new level
    }

    override fun setOutput(output: Logger.LogOutput) {
        outputs.clear()
        outputs.add(output)
    }

    private fun log(level: Logger.LogLevel, message: String, context: Map<String, Any>) {
        if (level.ordinal < minLevel.ordinal) return

        val entry = Logger.LogEntry(
            timestamp = System.currentTimeMillis(),
            level = level,
            message = message,
            context = context,
            thread = Thread.currentThread().name,
            buildId = buildId
        )

        for (out in outputs) {
            out.write(entry)
        }
    }

    private class ConsoleOutput(private val out: PrintStream) : Logger.LogOutput {
        private val formatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
            .withZone(ZoneId.of("UTC"))

        override fun write(entry: Logger.LogEntry) {
            val timestamp = formatter.format(Instant.ofEpochMilli(entry.timestamp))
            val prefix = when (entry.level) {
                Logger.LogLevel.TRACE -> "TRACE"
                Logger.LogLevel.DEBUG -> "DEBUG"
                Logger.LogLevel.INFO -> " INFO"
                Logger.LogLevel.WARN -> " WARN"
                Logger.LogLevel.ERROR -> "ERROR"
            }
            val ctx = if (entry.context.isNotEmpty())
                " ${entry.context.entries.joinToString(" ") { "${it.key}=${it.value}" }}"
            else ""

            out.println("[$timestamp] [$prefix] ${entry.message}$ctx")
        }

        override fun flush() = out.flush()
        override fun close() = out.close()
    }
}
