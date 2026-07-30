package com.hbe.api.exception

class SigningException(
    message: String,
    suggestion: String? = null,
    cause: Throwable? = null,
    details: List<String> = emptyList()
) : BuildException(
    errorCode = "SIGNING_ERROR",
    message = message,
    suggestion = suggestion,
    cause = cause,
    isRecoverable = false,
    details = details
)
