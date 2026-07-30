package com.hbe.api

interface Diagnostics {
    fun collect(): DiagnosticReport
    fun doctor(): HealthReport
    fun suggestFix(error: com.hbe.api.dto.BuildError): String
    fun analyzeLogs(logPath: java.nio.file.Path): LogAnalysis
    fun buildAnalytics(buildId: String): BuildAnalytics?
}

data class DiagnosticReport(
    val status: HealthStatus,
    val checks: Map<String, HealthCheck>,
    val recommendations: List<String> = emptyList()
)

data class HealthReport(
    val status: HealthStatus,
    val checks: Map<String, HealthCheck>,
    val recommendations: List<String> = emptyList()
)

enum class HealthStatus {
    OK,
    WARNING,
    ERROR
}

data class HealthCheck(
    val status: HealthStatus,
    val message: String,
    val details: Map<String, Any> = emptyMap()
)

data class LogAnalysis(
    val totalEntries: Int = 0,
    val errors: Int = 0,
    val warnings: Int = 0,
    val timeline: List<LogTimelineEntry> = emptyList()
)

data class LogTimelineEntry(
    val timestamp: Long,
    val level: String,
    val message: String
)

data class BuildAnalytics(
    val buildId: String,
    val projectName: String = "",
    val variant: String = "",
    val totalDurationMs: Long = 0,
    val ramPeakMb: Int = 0,
    val ramAverageMb: Int = 0,
    val cacheHitRate: Double = 0.0,
    val batchCount: Int = 0,
    val totalFiles: Int = 0,
    val methodCount: Int = 0,
    val dexCount: Int = 0,
    val apkSize: Long = 0
)
