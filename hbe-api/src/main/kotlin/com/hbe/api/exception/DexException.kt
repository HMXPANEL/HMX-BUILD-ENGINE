package com.hbe.api.exception

class DexException(
    message: String,
    suggestion: String? = null,
    cause: Throwable? = null,
    details: List<String> = emptyList()
) : BuildException(
    errorCode = "DEX_ERROR",
    message = message,
    suggestion = suggestion,
    cause = cause,
    isRecoverable = false,
    details = details
)
