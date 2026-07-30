package com.hbe.api.exception

class ResourceException(
    message: String,
    suggestion: String? = null,
    cause: Throwable? = null,
    details: List<String> = emptyList()
) : BuildException(
    errorCode = "RESOURCE_ERROR",
    message = message,
    suggestion = suggestion,
    cause = cause,
    isRecoverable = false,
    details = details
)
