package com.hbe.sdk

import com.hbe.api.*
import com.hbe.api.exception.SdkException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.ZipInputStream

open class SdkInstallerImpl(
    private val networkClient: NetworkClient,
    private val fileSystem: FileSystem,
    private val logger: Logger,
    private val options: SdkInstallOptions = SdkInstallOptions(
        sdkRoot = Path.of(System.getProperty("user.home", "."), ".hbe", "sdk"),
        cacheDir = Path.of(System.getProperty("user.home", "."), ".hbe", "cache")
    )
) : SdkInstaller {

    private val cacheDir: Path = options.cacheDir.resolve("downloads")
    private val manifestFile: Path = options.sdkRoot.resolve("manifest.json")
    private val cacheManifestFile: Path = cacheDir.resolve("cache.json")

    init {
        Files.createDirectories(options.sdkRoot)
        Files.createDirectories(cacheDir)
    }

    override fun installPlatform(
        apiLevel: Int,
        callback: ProgressCallback?,
        token: CancellationToken?
    ): SdkInstallResult {
        checkCancelled(token)
        val platformDir = options.sdkRoot.resolve("platforms").resolve("android-$apiLevel")
        if (Files.isDirectory(platformDir) && !options.forceReinstall) {
            logger.info("Platform android-$apiLevel already installed", emptyMap())
            return SdkInstallResult(true, "platform", apiLevel.toString(), platformDir, fromCache = true)
        }

        val url = platformDownloadUrl(apiLevel, options.osName)
        val fileName = "platform-$apiLevel-${options.osName}.zip"
        return installComponent("platform", apiLevel.toString(), url, fileName, platformDir, callback, token)
    }

    override fun installBuildTools(
        version: String,
        callback: ProgressCallback?,
        token: CancellationToken?
    ): SdkInstallResult {
        checkCancelled(token)
        val btDir = options.sdkRoot.resolve("build-tools").resolve(version)
        if (Files.isDirectory(btDir) && !options.forceReinstall) {
            logger.info("Build-tools $version already installed", emptyMap())
            return SdkInstallResult(true, "build-tools", version, btDir, fromCache = true)
        }

        val url = buildToolsDownloadUrl(version, options.osName)
        val fileName = "build-tools-$version-${options.osName}.zip"
        return installComponent("build-tools", version, url, fileName, btDir, callback, token)
    }

    override fun getInstalledPlatforms(): List<Int> {
        val manifest = readManifest()
        return manifest["platforms"]?.keys?.mapNotNull { it.toIntOrNull() }?.sorted() ?: emptyList()
    }

    override fun getInstalledBuildTools(): List<String> {
        val manifest = readManifest()
        return manifest["buildTools"]?.keys?.sortedDescending() ?: emptyList()
    }

    override fun getCacheSize(): Long {
        if (!Files.isDirectory(cacheDir)) return 0
        return Files.walk(cacheDir)
            .filter { Files.isRegularFile(it) }
            .mapToLong { Files.size(it) }
            .sum()
    }

    override fun clearCache() {
        if (Files.isDirectory(cacheDir)) {
            Files.walk(cacheDir)
                .sorted(java.util.Comparator.reverseOrder())
                .forEach { Files.deleteIfExists(it) }
        }
        Files.createDirectories(cacheDir)
        writeJson(cacheManifestFile, mapOf<String, Any>())
    }

    private fun installComponent(
        componentType: String,
        version: String,
        url: String,
        fileName: String,
        targetDir: Path,
        callback: ProgressCallback?,
        token: CancellationToken?
    ): SdkInstallResult {
        val zipPath = cacheDir.resolve(fileName)
        val sha256Path = cacheDir.resolve("$fileName.sha256")

        callback?.onProgress(0, null)

        var fromCache = false
        var wasResumed = false
        var bytesDownloaded = 0L

        if (checkCacheValid(zipPath)) {
            logger.info("Using cached download", mapOf("file" to fileName))
            fromCache = true
        } else {
            callback?.onProgress(0, null)
            val downloadResult = downloadWithResume(url, zipPath, callback, token)
            bytesDownloaded = downloadResult.bytesDownloaded
            wasResumed = downloadResult.wasResumed
        }

        checkCancelled(token)

        if (options.verifySha256) {
            callback?.onProgress(bytesDownloaded, bytesDownloaded)
            val sha256 = downloadSha256(url, sha256Path, token)
            if (sha256 != null) {
                verifySha256(zipPath, sha256)
            }
        }

        checkCancelled(token)
        callback?.onProgress(bytesDownloaded, bytesDownloaded)

        Files.createDirectories(targetDir.parent)
        extractZip(zipPath, targetDir)

        updateManifest(componentType, version)
        writeCacheManifestEntry(fileName)

        callback?.onProgress(bytesDownloaded, bytesDownloaded)

        return SdkInstallResult(
            success = true,
            component = componentType,
            version = version,
            installPath = targetDir,
            fromCache = fromCache,
            bytesDownloaded = bytesDownloaded,
            wasResumed = wasResumed
        )
    }

    internal open fun downloadWithResume(
        url: String,
        destination: Path,
        callback: ProgressCallback?,
        token: CancellationToken?
    ): DownloadTransfer {
        Files.createDirectories(destination.parent)

        val existingSize = if (Files.exists(destination)) Files.size(destination) else 0L

        val conn = URI.create(url).toURL().openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 30000

        if (existingSize > 0) {
            conn.setRequestProperty("Range", "bytes=$existingSize-")
        }

        conn.connect()
        checkCancelled(token)

        val statusCode = conn.responseCode
        val contentLength = conn.contentLengthLong
        val totalBytes = if (statusCode == 206) {
            existingSize + maxOf(contentLength, 0L)
        } else {
            maxOf(contentLength, 0L)
        }

        val wasResumed = statusCode == 206
        if (!wasResumed && existingSize > 0) {
            Files.delete(destination)
        }

        val inputStream: InputStream = conn.inputStream ?: conn.errorStream
        val outputStream = if (wasResumed) {
            java.io.FileOutputStream(destination.toFile(), true)
        } else {
            Files.newOutputStream(destination)
        }

        var downloaded = existingSize
        val buffer = ByteArray(8192)
        val startTime = System.currentTimeMillis()

        try {
            outputStream.use { out ->
                var bytesRead = inputStream.read(buffer)
                while (bytesRead >= 0) {
                    checkCancelled(token)
                    out.write(buffer, 0, bytesRead)
                    downloaded += bytesRead
                    callback?.onProgress(downloaded, if (totalBytes > 0) totalBytes else null)
                    bytesRead = inputStream.read(buffer)
                }
            }
        } catch (e: InterruptedIOException) {
            throw e
        } finally {
            inputStream.close()
            conn.disconnect()
        }

        return DownloadTransfer(bytesDownloaded = downloaded, wasResumed = wasResumed)
    }

    private fun downloadSha256(url: String, destination: Path, token: CancellationToken?): String? {
        val shaUrl = "$url.sha256"
        return try {
            val response = networkClient.get(shaUrl)
            checkCancelled(token)
            if (response.isSuccess) response.bodyAsString.trim() else null
        } catch (_: Exception) {
            null
        }
    }

    private fun verifySha256(file: Path, expectedSha256: String) {
        val actualSha256 = computeSha256(file)
        if (!actualSha256.equals(expectedSha256, ignoreCase = true)) {
            Files.deleteIfExists(file)
            throw SdkException(
                message = "SHA-256 mismatch for ${file.fileName}",
                suggestion = "The download may be corrupted. Try again or disable verification."
            )
        }
    }

    private fun checkCacheValid(path: Path): Boolean {
        if (!Files.exists(path)) return false
        val cacheManifest = readJson(cacheManifestFile)
        val entry = cacheManifest[path.fileName.toString()] as? Map<*, *> ?: return false
        val cachedSha = entry["sha256"] as? String
        if (cachedSha == null) return false
        return try {
            val actualSha = computeSha256(path)
            actualSha.equals(cachedSha, ignoreCase = true)
        } catch (_: Exception) {
            false
        }
    }

    private fun extractZip(zipPath: Path, targetDir: Path) {
        if (Files.exists(targetDir)) {
            Files.walk(targetDir)
                .sorted(java.util.Comparator.reverseOrder())
                .forEach { Files.deleteIfExists(it) }
        }

        ZipInputStream(Files.newInputStream(zipPath)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val entryPath = targetDir.resolve(entry.name)
                if (entry.isDirectory) {
                    Files.createDirectories(entryPath)
                } else {
                    Files.createDirectories(entryPath.parent)
                    Files.copy(zis, entryPath, StandardCopyOption.REPLACE_EXISTING)
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    private fun updateManifest(componentType: String, version: String) {
        val manifest = readManifest().toMutableMap()
        val components = (manifest[componentType] as? MutableMap<String, Any>) ?: mutableMapOf()
        components[version] = mapOf("installDate" to System.currentTimeMillis())
        manifest[componentType] = components
        writeManifest(manifest)
    }

    private fun writeCacheManifestEntry(fileName: String) {
        val path = cacheDir.resolve(fileName)
        if (Files.exists(path)) {
            val manifest = readJson(cacheManifestFile).toMutableMap()
            manifest[fileName] = mapOf(
                "sha256" to computeSha256(path),
                "size" to Files.size(path),
                "cachedAt" to System.currentTimeMillis()
            )
            writeJson(cacheManifestFile, manifest)
        }
    }

    private fun readManifest(): Map<String, Map<String, Any>> {
        if (!Files.exists(manifestFile)) return emptyMap()
        return try {
            val text = Files.readString(manifestFile)
            val mapper = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()
            mapper.readValue(text, Map::class.java) as? Map<String, Map<String, Any>> ?: emptyMap()
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun writeManifest(data: Map<String, Any>) {
        try {
            val mapper = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()
            val json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(data)
            Files.createDirectories(manifestFile.parent)
            Files.writeString(manifestFile, json)
        } catch (e: Exception) {
            logger.warn("Failed to write SDK manifest", mapOf("error" to (e.message ?: "")))
        }
    }

    private fun readJson(path: Path): Map<String, Any> {
        if (!Files.exists(path)) return emptyMap()
        return try {
            val mapper = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()
            mapper.readValue(Files.readString(path), Map::class.java) as? Map<String, Any> ?: emptyMap()
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun writeJson(path: Path, data: Map<String, Any>) {
        try {
            val mapper = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()
            val json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(data)
            Files.createDirectories(path.parent)
            Files.writeString(path, json)
        } catch (_: Exception) {}
    }

    internal fun computeSha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { stream ->
            val buffer = ByteArray(8192)
            var bytesRead = stream.read(buffer)
            while (bytesRead >= 0) {
                if (bytesRead > 0) digest.update(buffer, 0, bytesRead)
                bytesRead = stream.read(buffer)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    internal fun checkCancelled(token: CancellationToken?) {
        if (token != null && token.isCancelled) {
            throw SdkException("SDK installation cancelled by user")
        }
    }

    internal data class DownloadTransfer(
        val bytesDownloaded: Long,
        val wasResumed: Boolean
    )

    companion object {
        fun platformDownloadUrl(apiLevel: Int, os: String): String {
            return "https://dl.google.com/android/repository/platform-${apiLevel}_r01.zip"
        }

        fun buildToolsDownloadUrl(version: String, os: String): String {
            return "https://dl.google.com/android/repository/build-tools_r${version}-${os}.zip"
        }
    }
}

private class InterruptedIOException(message: String) : java.io.IOException(message)
