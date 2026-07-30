package com.hbe.api

import java.nio.file.Path

interface NetworkClient {
    fun get(url: String, config: RequestConfig = RequestConfig()): HttpResponse
    fun download(url: String, destination: Path, config: RequestConfig = RequestConfig())
    fun download(url: String, destination: Path, callback: ProgressCallback, config: RequestConfig = RequestConfig())
    fun ping(url: String): Boolean
}

data class RequestConfig(
    val connectTimeoutMs: Int = 10000,
    val readTimeoutMs: Int = 30000,
    val maxRetries: Int = 3,
    val headers: Map<String, String> = emptyMap(),
    val followRedirects: Boolean = true,
    val proxy: ProxyConfig? = null
)

data class HttpResponse(
    val statusCode: Int,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray = ByteArray(0),
    val url: String = ""
) {
    val bodyAsString: String get() = String(body, Charsets.UTF_8)
    val isSuccess: Boolean get() = statusCode in 200..299
    val isRedirect: Boolean get() = statusCode in 300..399
    val isClientError: Boolean get() = statusCode in 400..499
    val isServerError: Boolean get() = statusCode in 500..599
}

fun interface ProgressCallback {
    fun onProgress(bytesDownloaded: Long, totalBytes: Long?)
}
