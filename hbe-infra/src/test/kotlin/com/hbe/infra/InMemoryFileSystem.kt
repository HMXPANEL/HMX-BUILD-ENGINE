package com.hbe.infra

import com.hbe.api.FileMetadata
import com.hbe.api.FileSystem
import java.io.Closeable
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

class InMemoryFileSystem : FileSystem {

    private val files = ConcurrentHashMap<String, ByteArray>()
    private val dirs = ConcurrentHashMap<String, MutableSet<String>>()

    init {
        dirs["/"] = mutableSetOf()
    }

    override fun readAllBytes(path: Path): ByteArray {
        return files[normalize(path)] ?: throw java.nio.file.NoSuchFileException(path.toString())
    }

    override fun writeBytes(path: Path, data: ByteArray) {
        val norm = normalize(path)
        files[norm] = data
        ensureParentDirs(norm)
    }

    override fun exists(path: Path): Boolean = files.containsKey(normalize(path))

    override fun delete(path: Path) {
        files.remove(normalize(path))
    }

    override fun deleteRecursively(path: Path) {
        val prefix = normalize(path)
        files.keys.removeAll { it.startsWith(prefix) }
    }

    override fun copy(source: Path, target: Path) {
        val data = readAllBytes(source)
        writeBytes(target, data)
    }

    override fun move(source: Path, target: Path) {
        val data = readAllBytes(source)
        writeBytes(target, data)
        delete(source)
    }

    override fun size(path: Path): Long {
        return files[normalize(path)]?.size?.toLong() ?: 0L
    }

    override fun createDirectories(path: Path) {
        val norm = normalize(path)
        dirs.putIfAbsent(norm, mutableSetOf())
        ensureParentDirs(norm)
    }

    override fun listFiles(dir: Path, glob: String): List<Path> {
        val norm = normalize(dir)
        val dirFiles = dirs[norm] ?: return emptyList()
        return dirFiles.map { Path.of(it) }
    }

    override fun walkFiles(dir: Path, glob: String): List<Path> {
        val norm = normalize(dir)
        return files.keys
            .filter { it.startsWith(norm) }
            .map { Path.of(it) }
    }

    override fun lastModified(path: Path): Long = System.currentTimeMillis()

    override fun createTempFile(prefix: String, suffix: String): Path {
        val path = Path.of("/tmp/${prefix}${System.nanoTime()}$suffix")
        writeBytes(path, ByteArray(0))
        return path
    }

    override fun createTempDirectory(prefix: String): Path {
        val path = Path.of("/tmp/${prefix}${System.nanoTime()}")
        createDirectories(path)
        return path
    }

    override fun acquireLock(path: Path): Closeable = Closeable {}

    override fun metadata(path: Path): FileMetadata {
        val norm = normalize(path)
        val data = files[norm]
        return FileMetadata(
            path = path,
            size = data?.size?.toLong() ?: 0L,
            lastModified = System.currentTimeMillis(),
            isDirectory = dirs.containsKey(norm),
            isFile = files.containsKey(norm),
            isSymbolicLink = false
        )
    }

    fun clear() {
        files.clear()
        dirs.clear()
        dirs["/"] = mutableSetOf()
    }

    private fun normalize(path: Path): String {
        return path.toAbsolutePath().normalize().toString()
    }

    private fun ensureParentDirs(path: String) {
        val parent = Path.of(path).parent?.toString() ?: return
        dirs.putIfAbsent(parent, mutableSetOf())
        val fileName = Path.of(path).fileName.toString()
        dirs.computeIfAbsent(parent) { mutableSetOf() }.add(fileName)
    }
}
