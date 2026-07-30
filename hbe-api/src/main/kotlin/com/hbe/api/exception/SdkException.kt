package com.hbe.api.exception

class SdkException(
    message: String,
    suggestion: String? = null,
    cause: Throwable? = null,
    details: List<String> = emptyList()
) : BuildException(
    errorCode = "SDK_ERROR",
    message = message,
    suggestion = suggestion,
    cause = cause,
    isRecoverable = false,
    details = details
) {
    constructor(sdkVersion: Int) : this(
        message = "SDK platform android-$sdkVersion not found",
        suggestion = "Run 'hbe doctor' to check SDK status or use auto-download"
    )
}
