package com.hbe.api.exception

class ConfigurationException(
    message: String,
    suggestion: String? = null,
    details: List<String> = emptyList()
) : BuildException(
    errorCode = "CONFIG_ERROR",
    message = message,
    suggestion = suggestion,
    cause = null,
    isRecoverable = false,
    details = details
)
