package com.hbe.api.event

sealed class BuildEvent {
    abstract val timestamp: Long
    abstract val buildId: String
    abstract val phase: String
    abstract val durationMs: Long?
    abstract val progress: Float?
    abstract val metadata: Map<String, Any>
}

// Lifecycle
data class BuildStartedEvent(
    override val buildId: String,
    override val phase: String = "BUILD",
    override val timestamp: Long = System.currentTimeMillis(),
    override val durationMs: Long? = null,
    override val progress: Float? = null,
    override val metadata: Map<String, Any> = emptyMap()
) : BuildEvent()

data class BuildFinishedEvent(
    override val buildId: String,
    override val phase: String = "BUILD",
    override val timestamp: Long = System.currentTimeMillis(),
    override val durationMs: Long? = null,
    override val progress: Float? = null,
    override val metadata: Map<String, Any> = emptyMap()
) : BuildEvent()

data class BuildFailedEvent(
    override val buildId: String,
    override val phase: String = "BUILD",
    override val timestamp: Long = System.currentTimeMillis(),
    override val durationMs: Long? = null,
    override val progress: Float? = null,
    override val metadata: Map<String, Any> = emptyMap()
) : BuildEvent()

data class BuildCancelledEvent(
    override val buildId: String,
    override val phase: String = "BUILD",
    override val timestamp: Long = System.currentTimeMillis(),
    override val durationMs: Long? = null,
    override val progress: Float? = null,
    override val metadata: Map<String, Any> = emptyMap()
) : BuildEvent()

// Generic phase markers
data class PhaseStartedEvent(
    override val buildId: String,
    override val phase: String,
    override val timestamp: Long = System.currentTimeMillis(),
    override val durationMs: Long? = null,
    override val progress: Float? = null,
    override val metadata: Map<String, Any> = emptyMap()
) : BuildEvent()

data class PhaseFinishedEvent(
    override val buildId: String,
    override val phase: String,
    override val timestamp: Long = System.currentTimeMillis(),
    override val durationMs: Long? = null,
    override val progress: Float? = null,
    override val metadata: Map<String, Any> = emptyMap()
) : BuildEvent()

// Resource pipeline
data class ResourceCompilationStartedEvent(
    override val buildId: String,
    override val phase: String = "RESOURCE_COMPILE",
    override val timestamp: Long = System.currentTimeMillis(),
    override val durationMs: Long? = null,
    override val progress: Float? = null,
    override val metadata: Map<String, Any> = emptyMap()
) : BuildEvent()

data class ResourceCompilationFinishedEvent(
    override val buildId: String,
    override val phase: String = "RESOURCE_COMPILE",
    override val timestamp: Long = System.currentTimeMillis(),
    override val durationMs: Long? = null,
    override val progress: Float? = null,
    override val metadata: Map<String, Any> = emptyMap()
) : BuildEvent()

data class ResourceLinkStartedEvent(
    override val buildId: String,
    override val phase: String = "RESOURCE_LINK",
    override val timestamp: Long = System.currentTimeMillis(),
    override val durationMs: Long? = null,
    override val progress: Float? = null,
    override val metadata: Map<String, Any> = emptyMap()
) : BuildEvent()

data class ResourceLinkFinishedEvent(
    override val buildId: String,
    override val phase: String = "RESOURCE_LINK",
    override val timestamp: Long = System.currentTimeMillis(),
    override val durationMs: Long? = null,
    override val progress: Float? = null,
    override val metadata: Map<String, Any> = emptyMap()
) : BuildEvent()

// Source compilation
data class JavaCompilationStartedEvent(
    override val buildId: String,
    override val phase: String = "JAVA_COMPILE",
    override val timestamp: Long = System.currentTimeMillis(),
    override val durationMs: Long? = null,
    override val progress: Float? = null,
    override val metadata: Map<String, Any> = emptyMap()
) : BuildEvent()

data class JavaCompilationFinishedEvent(
    override val buildId: String,
    override val phase: String = "JAVA_COMPILE",
    override val timestamp: Long = System.currentTimeMillis(),
    override val durationMs: Long? = null,
    override val progress: Float? = null,
    override val metadata: Map<String, Any> = emptyMap()
) : BuildEvent()

data class KotlinCompilationStartedEvent(
    override val buildId: String,
    override val phase: String = "KOTLIN_COMPILE",
    override val timestamp: Long = System.currentTimeMillis(),
    override val durationMs: Long? = null,
    override val progress: Float? = null,
    override val metadata: Map<String, Any> = emptyMap()
) : BuildEvent()

data class KotlinCompilationFinishedEvent(
    override val buildId: String,
    override val phase: String = "KOTLIN_COMPILE",
    override val timestamp: Long = System.currentTimeMillis(),
    override val durationMs: Long? = null,
    override val progress: Float? = null,
    override val metadata: Map<String, Any> = emptyMap()
) : BuildEvent()

// Dex generation
data class DexGenerationStartedEvent(
    override val buildId: String,
    override val phase: String = "DEX",
    override val timestamp: Long = System.currentTimeMillis(),
    override val durationMs: Long? = null,
    override val progress: Float? = null,
    override val metadata: Map<String, Any> = emptyMap()
) : BuildEvent()

data class DexGenerationFinishedEvent(
    override val buildId: String,
    override val phase: String = "DEX",
    override val timestamp: Long = System.currentTimeMillis(),
    override val durationMs: Long? = null,
    override val progress: Float? = null,
    override val metadata: Map<String, Any> = emptyMap()
) : BuildEvent()

// Packaging
data class PackagingStartedEvent(
    override val buildId: String,
    override val phase: String = "PACKAGE",
    override val timestamp: Long = System.currentTimeMillis(),
    override val durationMs: Long? = null,
    override val progress: Float? = null,
    override val metadata: Map<String, Any> = emptyMap()
) : BuildEvent()

data class PackagingFinishedEvent(
    override val buildId: String,
    override val phase: String = "PACKAGE",
    override val timestamp: Long = System.currentTimeMillis(),
    override val durationMs: Long? = null,
    override val progress: Float? = null,
    override val metadata: Map<String, Any> = emptyMap()
) : BuildEvent()

// Signing
data class SigningStartedEvent(
    override val buildId: String,
    override val phase: String = "SIGN",
    override val timestamp: Long = System.currentTimeMillis(),
    override val durationMs: Long? = null,
    override val progress: Float? = null,
    override val metadata: Map<String, Any> = emptyMap()
) : BuildEvent()

data class SigningFinishedEvent(
    override val buildId: String,
    override val phase: String = "SIGN",
    override val timestamp: Long = System.currentTimeMillis(),
    override val durationMs: Long? = null,
    override val progress: Float? = null,
    override val metadata: Map<String, Any> = emptyMap()
) : BuildEvent()

// Tool execution
data class ToolExecutionStartedEvent(
    override val buildId: String,
    override val phase: String = "TOOL",
    override val timestamp: Long = System.currentTimeMillis(),
    override val durationMs: Long? = null,
    override val progress: Float? = null,
    override val metadata: Map<String, Any> = emptyMap()
) : BuildEvent()

data class ToolExecutionFinishedEvent(
    override val buildId: String,
    override val phase: String = "TOOL",
    override val timestamp: Long = System.currentTimeMillis(),
    override val durationMs: Long? = null,
    override val progress: Float? = null,
    override val metadata: Map<String, Any> = emptyMap()
) : BuildEvent()

// Downloads
data class DownloadStartedEvent(
    override val buildId: String,
    override val phase: String = "SDK_INSTALL",
    override val timestamp: Long = System.currentTimeMillis(),
    override val durationMs: Long? = null,
    override val progress: Float? = null,
    override val metadata: Map<String, Any> = emptyMap()
) : BuildEvent()

data class DownloadProgressEvent(
    override val buildId: String,
    override val phase: String = "SDK_INSTALL",
    override val timestamp: Long = System.currentTimeMillis(),
    override val durationMs: Long? = null,
    override val progress: Float? = null,
    override val metadata: Map<String, Any> = emptyMap()
) : BuildEvent()

data class DownloadFinishedEvent(
    override val buildId: String,
    override val phase: String = "SDK_INSTALL",
    override val timestamp: Long = System.currentTimeMillis(),
    override val durationMs: Long? = null,
    override val progress: Float? = null,
    override val metadata: Map<String, Any> = emptyMap()
) : BuildEvent()

// Cache
data class CacheHitEvent(
    override val buildId: String,
    override val phase: String,
    override val timestamp: Long = System.currentTimeMillis(),
    override val durationMs: Long? = null,
    override val progress: Float? = null,
    override val metadata: Map<String, Any> = emptyMap()
) : BuildEvent()

data class CacheMissEvent(
    override val buildId: String,
    override val phase: String,
    override val timestamp: Long = System.currentTimeMillis(),
    override val durationMs: Long? = null,
    override val progress: Float? = null,
    override val metadata: Map<String, Any> = emptyMap()
) : BuildEvent()

// Diagnostics
data class BuildWarningEvent(
    override val buildId: String,
    override val phase: String,
    override val timestamp: Long = System.currentTimeMillis(),
    override val durationMs: Long? = null,
    override val progress: Float? = null,
    override val metadata: Map<String, Any> = emptyMap()
) : BuildEvent()

data class BuildErrorEvent(
    override val buildId: String,
    override val phase: String,
    override val timestamp: Long = System.currentTimeMillis(),
    override val durationMs: Long? = null,
    override val progress: Float? = null,
    override val metadata: Map<String, Any> = emptyMap()
) : BuildEvent()
