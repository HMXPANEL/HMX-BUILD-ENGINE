package com.hbe.diag

import com.hbe.api.*
import com.hbe.api.dto.BuildError
import java.nio.file.Path

class DiagnosticsImpl(
    private val sdkManager: SdkManager,
    private val logger: Logger
) : Diagnostics {

    override fun collect(): DiagnosticReport {
        val checks = mutableMapOf<String, HealthCheck>()

        val sdkDiagnosis = sdkManager.doctor()
        checks["sdk"] = HealthCheck(
            status = if (sdkDiagnosis.sdkFound) HealthStatus.OK else HealthStatus.ERROR,
            message = if (sdkDiagnosis.sdkFound) "Android SDK found" else "Android SDK not found",
            details = mapOf("platforms" to sdkDiagnosis.platforms.toString())
        )

        checks["jdk"] = HealthCheck(
            status = if (sdkDiagnosis.jdkFound) HealthStatus.OK else HealthStatus.ERROR,
            message = if (sdkDiagnosis.jdkFound) "JDK found" else "JDK not found"
        )

        val recommendations = sdkDiagnosis.issues.map {
            when {
                it.contains("SDK") -> "Set ANDROID_HOME or run with auto-download"
                it.contains("JDK") -> "Install JDK 17+ or set JAVA_HOME"
                else -> "Run 'hbe doctor' for detailed diagnostics"
            }
        }

        val overallStatus = when {
            checks.values.any { it.status == HealthStatus.ERROR } -> HealthStatus.ERROR
            checks.values.any { it.status == HealthStatus.WARNING } -> HealthStatus.WARNING
            else -> HealthStatus.OK
        }

        return DiagnosticReport(
            status = overallStatus,
            checks = checks,
            recommendations = recommendations + sdkDiagnosis.recommendations
        )
    }

    override fun doctor(): HealthReport {
        val report = collect()
        return HealthReport(
            status = report.status,
            checks = report.checks,
            recommendations = report.recommendations
        )
    }

    override fun suggestFix(error: BuildError): String {
        return when (error.code) {
            "SDK_NOT_FOUND" -> "Android SDK not found. Set ANDROID_HOME or run 'hbe doctor'."
            "COMPILER_ERROR" -> "Fix the compilation error in ${error.phase} and rebuild."
            "DEPENDENCY_ERROR" -> "Check the dependency coordinate and repository access."
            "NETWORK_ERROR" -> "Check your network connection. If offline, use cached artifacts."
            "OUT_OF_MEMORY" -> "Build ran out of memory. Try increasing ramBudgetMb."
            "CONFIG_ERROR" -> "Invalid configuration. Check your hbe.json or config file."
            else -> "Unknown error. Check logs for details."
        }
    }

    override fun analyzeLogs(logPath: Path): LogAnalysis {
        if (!java.nio.file.Files.exists(logPath)) {
            return LogAnalysis(totalEntries = 0, errors = 0, warnings = 0)
        }

        val entries = java.nio.file.Files.readAllLines(logPath)
        var errors = 0
        var warnings = 0

        for (line in entries) {
            when {
                line.contains("\"ERROR\"") || line.contains("[ERROR]") -> errors++
                line.contains("\"WARN\"") || line.contains("[WARN]") -> warnings++
            }
        }

        return LogAnalysis(
            totalEntries = entries.size,
            errors = errors,
            warnings = warnings
        )
    }

    override fun buildAnalytics(buildId: String): BuildAnalytics? {
        // Will be implemented when build database is available
        return null
    }
}
