package com.hbe.api

import java.io.Closeable
import java.nio.file.Path

interface FileSystem {
    fun readAllBytes(path: Path): ByteArray
    fun writeBytes(path: Path, data: ByteArray)
    fun exists(path: Path): Boolean
    fun delete(path: Path)
    fun deleteRecursively(path: Path)
    fun copy(source: Path, target: Path)
    fun move(source: Path, target: Path)
    fun size(path: Path): Long
    fun createDirectories(path: Path)
    fun listFiles(dir: Path, glob: String): List<Path>
    fun walkFiles(dir: Path, glob: String): List<Path>
    fun lastModified(path: Path): Long
    fun createTempFile(prefix: String, suffix: String): Path
    fun createTempDirectory(prefix: String): Path
    fun acquireLock(path: Path): Closeable
    fun metadata(path: Path): FileMetadata
}

data class FileMetadata(
    val path: Path,
    val size: Long,
    val lastModified: Long,
    val isDirectory: Boolean,
    val isFile: Boolean,
    val isSymbolicLink: Boolean,
    val sha256: String = ""
)
