package com.hbe.api

interface Logger {
    fun debug(message: String, context: Map<String, Any> = emptyMap())
    fun info(message: String, context: Map<String, Any> = emptyMap())
    fun warn(message: String, context: Map<String, Any> = emptyMap())
    fun error(message: String, context: Map<String, Any> = emptyMap())
    fun trace(message: String, context: Map<String, Any> = emptyMap())
    fun setLevel(level: LogLevel)
    fun setOutput(output: LogOutput)

    enum class LogLevel {
        TRACE,
        DEBUG,
        INFO,
        WARN,
        ERROR
    }

    data class LogEntry(
        val timestamp: Long,
        val level: LogLevel,
        val message: String,
        val context: Map<String, Any>,
        val thread: String,
        val buildId: String? = null
    )

    interface LogOutput {
        fun write(entry: LogEntry)
        fun flush()
        fun close()
    }
}
