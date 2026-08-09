package com.hbe.infra

import com.hbe.api.FileMetadata
import com.hbe.api.FileSystem
import java.io.Closeable
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

class OsFileSystem : FileSystem {

    override fun readAllBytes(path: Path): ByteArray {
        return Files.readAllBytes(path)
    }

    override fun writeBytes(path: Path, data: ByteArray) {
        Files.createDirectories(path.parent)
        Files.write(path, data)
    }

    override fun exists(path: Path): Boolean = Files.exists(path)

    override fun delete(path: Path) {
        Files.deleteIfExists(path)
    }

    override fun deleteRecursively(path: Path) {
        if (Files.isDirectory(path)) {
            Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.delete(file)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                    Files.delete(dir)
                    return FileVisitResult.CONTINUE
                }
            })
        } else {
            Files.deleteIfExists(path)
        }
    }

    override fun copy(source: Path, target: Path) {
        Files.createDirectories(target.parent)
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
    }

    override fun move(source: Path, target: Path) {
        Files.createDirectories(target.parent)
        Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
    }

    override fun size(path: Path): Long = Files.size(path)

    override fun createDirectories(path: Path) {
        Files.createDirectories(path)
    }

    override fun listFiles(dir: Path, glob: String): List<Path> {
        if (!Files.isDirectory(dir)) return emptyList()
        val matcher = if (glob.isNotEmpty()) FileSystems.getDefault().getPathMatcher("glob:$glob") else null
        return Files.list(dir).use { stream ->
            stream.filter { matcher?.matches(it.fileName) != false }.toList()
        }
    }

    override fun walkFiles(dir: Path, glob: String): List<Path> {
        if (!Files.isDirectory(dir)) return emptyList()
        val matcher = if (glob.isNotEmpty()) FileSystems.getDefault().getPathMatcher("glob:$glob") else null
        val result = Files.walk(dir).use { stream ->
            stream.filter { Files.isRegularFile(it) }
                .filter { file ->
                    val matches = matcher?.matches(file.fileName) != false
                                        matches
                }
                .toList()
        }
                return result
    }

    override fun lastModified(path: Path): Long {
        return Files.getLastModifiedTime(path).toMillis()
    }

    override fun createTempFile(prefix: String, suffix: String): Path {
        return Files.createTempFile(prefix, suffix)
    }

    override fun createTempDirectory(prefix: String): Path {
        return Files.createTempDirectory(prefix)
    }

    override fun acquireLock(path: Path): Closeable {
        val file = path.toFile()
        file.parentFile?.mkdirs()
        val channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
        return try {
            val lock = channel.tryLock()
            if (lock == null) throw IOException("Could not acquire lock on $path")
            LockWrapper(channel, lock)
        } catch (e: OverlappingFileLockException) {
            channel.close()
            throw IOException("Lock already held on $path", e)
        }
    }

    override fun metadata(path: Path): FileMetadata {
        val attrs = Files.readAttributes(path, BasicFileAttributes::class.java)
        val sha256 = if (attrs.isRegularFile && attrs.size() > 0L) {
            computeSha256(path)
        } else ""
        return FileMetadata(
            path = path.toAbsolutePath().normalize(),
            size = attrs.size(),
            lastModified = attrs.lastModifiedTime().toMillis(),
            isDirectory = attrs.isDirectory,
            isFile = attrs.isRegularFile,
            isSymbolicLink = attrs.isSymbolicLink,
            sha256 = sha256
        )
    }

    private fun computeSha256(path: Path): String {
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

    private class LockWrapper(
        private val channel: FileChannel,
        private val lock: FileLock
    ) : Closeable {
        override fun close() {
            try {
                lock.release()
            } finally {
                channel.close()
            }
        }
    }
}
