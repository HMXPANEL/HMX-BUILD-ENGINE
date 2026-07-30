package com.hbe.infra

import com.hbe.api.NetworkClient
import com.hbe.api.RequestConfig
import com.hbe.api.ProgressCallback
import com.hbe.api.exception.NetworkException
import com.hbe.api.HttpResponse as HbeHttpResponse
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Duration

class JavaNetHttpClient : NetworkClient {

    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    override fun get(url: String, config: RequestConfig): HbeHttpResponse {
        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofMillis(config.readTimeoutMs.toLong()))
            .GET()
            .build()

        val response = client.send(request, HttpResponse.BodyHandlers.ofByteArray())

        val headers = response.headers().map().entries
            .associate { (key, values) -> key to values.joinToString(", ") }

        return HbeHttpResponse(
            statusCode = response.statusCode(),
            headers = headers,
            body = response.body(),
            url = url
        )
    }

    override fun download(url: String, destination: Path, config: RequestConfig) {
        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofMillis(config.readTimeoutMs.toLong()))
            .GET()
            .build()

        destination.parent?.let { java.nio.file.Files.createDirectories(it) }

        val response = client.send(request, HttpResponse.BodyHandlers.ofFile(destination))

        if (response.statusCode() !in 200..299) {
            throw NetworkException(
                message = "Download failed: HTTP ${response.statusCode()}",
                url = url,
                statusCode = response.statusCode()
            )
        }
    }

    override fun download(
        url: String,
        destination: Path,
        callback: ProgressCallback,
        config: RequestConfig
    ) {
        // Simple implementation: download without progress tracking
        download(url, destination, config)
        callback.onProgress(java.nio.file.Files.size(destination), java.nio.file.Files.size(destination))
    }

    override fun ping(url: String): Boolean {
        return try {
            val response = get(url, RequestConfig(connectTimeoutMs = 5000, readTimeoutMs = 5000))
            response.isSuccess || response.isRedirect
        } catch (e: Exception) {
            false
        }
    }
}
