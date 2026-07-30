package com.hbe.api.exception

class NetworkException(
    message: String,
    suggestion: String? = null,
    cause: Throwable? = null,
    val url: String? = null,
    val statusCode: Int? = null,
    details: List<String> = emptyList()
) : BuildException(
    errorCode = "NETWORK_ERROR",
    message = message,
    suggestion = suggestion,
    cause = cause,
    isRecoverable = true,
    details = details
)
