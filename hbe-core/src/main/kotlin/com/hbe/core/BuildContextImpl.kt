package com.hbe.core

import com.hbe.api.*
import com.hbe.api.dto.BuildRequest
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

class BuildContextImpl(
    override val buildId: String,
    override val request: BuildRequest,
    override val config: EngineConfig,
    override val cancellationToken: CancellationToken = CancellationTokenImpl()
) : BuildContext {

    override val startTime: Instant = Instant.now()
    private val metadataStore = ConcurrentHashMap<String, Any>()
    private val stateStore = ConcurrentHashMap<String, Any>()

    override fun metadata(key: String): Any? = metadataStore[key]

    override fun setMetadata(key: String, value: Any) {
        metadataStore[key] = value
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T> getState(key: String, type: Class<T>): T? {
        val value = stateStore[key] ?: return null
        return if (type.isInstance(value)) value as T else null
    }

    override fun setState(key: String, value: Any) {
        stateStore[key] = value
    }
}
