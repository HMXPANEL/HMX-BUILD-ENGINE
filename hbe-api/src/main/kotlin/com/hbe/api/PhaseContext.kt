package com.hbe.api

import com.hbe.api.dto.BuildRequest

interface PhaseContext {
    val buildRequest: BuildRequest
    val buildContext: BuildContext
    val fileSystem: FileSystem
    val processRunner: ProcessRunner
    val cacheManager: CacheManager
    val memoryMonitor: MemoryMonitor
    val logger: Logger
    val cancellationToken: CancellationToken

    fun <T> getState(key: String, type: Class<T>): T?
    fun setState(key: String, value: Any)
    fun <T> getConfig(key: String, type: Class<T>, default: T): T
}
