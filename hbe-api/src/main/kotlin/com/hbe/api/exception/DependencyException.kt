package com.hbe.api.exception

class DependencyException(
    message: String,
    val coordinate: String? = null,
    val repository: String? = null,
    suggestion: String? = null,
    cause: Throwable? = null
) : BuildException(
    errorCode = "DEPENDENCY_ERROR",
    message = message,
    suggestion = suggestion,
    cause = cause,
    isRecoverable = true,
    details = listOfNotNull(coordinate, repository).map { "$it" }
)
