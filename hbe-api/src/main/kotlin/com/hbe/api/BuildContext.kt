package com.hbe.api

import com.hbe.api.dto.BuildRequest
import java.time.Instant
import java.util.UUID

interface BuildContext {
    val buildId: String
    val request: BuildRequest
    val config: EngineConfig
    val startTime: Instant
    val cancellationToken: CancellationToken

    fun metadata(key: String): Any?
    fun setMetadata(key: String, value: Any)
    fun <T> getState(key: String, type: Class<T>): T?
    fun setState(key: String, value: Any)
}
