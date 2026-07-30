package com.hbe.api.exception

class CacheException(
    message: String,
    suggestion: String? = null,
    cause: Throwable? = null,
    details: List<String> = emptyList()
) : BuildException(
    errorCode = "CACHE_ERROR",
    message = message,
    suggestion = suggestion,
    cause = cause,
    isRecoverable = true,
    details = details
)
