package com.hbe.api.exception

open class BuildException(
    override val message: String,
    val errorCode: String,
    val suggestion: String? = null,
    override val cause: Throwable? = null,
    val isRecoverable: Boolean = false,
    val details: List<String> = emptyList()
) : Exception(message, cause) {

    override fun toString(): String {
        return "[$errorCode] $message" +
            (if (suggestion != null) " | Suggestion: $suggestion" else "") +
            (if (details.isNotEmpty()) " | Details: ${details.joinToString("; ")}" else "")
    }
}
