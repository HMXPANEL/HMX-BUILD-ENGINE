package com.hbe.api.exception

class CompilerException(
    message: String,
    val phase: String,
    val errors: List<CompilerError> = emptyList(),
    suggestion: String? = null,
    cause: Throwable? = null
) : BuildException(
    errorCode = "COMPILER_ERROR",
    message = message,
    suggestion = suggestion,
    cause = cause,
    isRecoverable = false,
    details = errors.map { it.toString() }
)

data class CompilerError(
    val file: String,
    val line: Int,
    val column: Int,
    val message: String,
    val toolOutput: String? = null
) {
    override fun toString(): String = "$file:$line:$column: $message"
}
