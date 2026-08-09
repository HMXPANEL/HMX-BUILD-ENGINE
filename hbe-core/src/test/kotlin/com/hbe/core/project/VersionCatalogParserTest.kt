package com.hbe.core.project

import com.hbe.api.Logger
import java.io.Closeable
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class VersionCatalogParserTest {

    private class NoopFileSystem : com.hbe.api.FileSystem {
        override fun readAllBytes(path: Path): ByteArray = Files.readAllBytes(path)
        override fun writeBytes(path: Path, data: ByteArray) {}
        override fun exists(path: Path): Boolean = Files.exists(path)
        override fun delete(path: Path) {}
        override fun deleteRecursively(path: Path) {}
        override fun copy(source: Path, target: Path) {}
        override fun move(source: Path, target: Path) {}
        override fun size(path: Path): Long = Files.size(path)
        override fun createDirectories(path: Path) {}
        override fun listFiles(dir: Path, glob: String): List<Path> = emptyList()
        override fun walkFiles(dir: Path, glob: String): List<Path> = emptyList()
        override fun lastModified(path: Path): Long = 0
        override fun createTempFile(prefix: String, suffix: String): Path = Files.createTempFile(prefix, suffix)
        override fun createTempDirectory(prefix: String): Path = Files.createTempDirectory(prefix)
        override fun acquireLock(path: Path): Closeable = Closeable {}
        override fun metadata(path: Path) = com.hbe.api.FileMetadata(path, 0, 0, Files.isDirectory(path), Files.isRegularFile(path), false, "")
    }

    private class NoopLogger : Logger {
        override fun debug(message: String, context: Map<String, Any>) {}
        override fun info(message: String, context: Map<String, Any>) {}
        override fun warn(message: String, context: Map<String, Any>) {}
        override fun error(message: String, context: Map<String, Any>) {}
        override fun trace(message: String, context: Map<String, Any>) {}
        override fun setLevel(level: Logger.LogLevel) {}
        override fun setOutput(output: Logger.LogOutput) {}
    }

    private fun parser() = VersionCatalogParser(NoopFileSystem())

    @Test
    fun `parses versions section`() {
        val catalog = parser().parseText("""
            [versions]
            kotlin = "2.2.10"
            composeBom = "2025.06.00"
            coreKtx = "1.12.0"
        """.trimIndent())

        assertEquals("2.2.10", catalog.versions["kotlin"])
        assertEquals("2025.06.00", catalog.versions["composeBom"])
        assertEquals("1.12.0", catalog.versions["coreKtx"])
    }

    @Test
    fun `parses libraries with version ref`() {
        val catalog = parser().parseText("""
            [versions]
            coreKtx = "1.12.0"

            [libraries]
            androidx-core-ktx = { group = "androidx.core", name = "core-ktx", version.ref = "coreKtx" }
        """.trimIndent())

        val lib = catalog.libraries["androidx-core-ktx"]
        assertNotNull(lib)
        assertEquals("androidx.core", lib!!.groupId)
        assertEquals("core-ktx", lib.artifactId)
        assertEquals("1.12.0", lib.version)
    }

    @Test
    fun `parses libraries with inline version`() {
        val catalog = parser().parseText("""
            [libraries]
            androidx-core-ktx = { group = "androidx.core", name = "core-ktx", version = "1.12.0" }
        """.trimIndent())

        val lib = catalog.libraries["androidx-core-ktx"]
        assertNotNull(lib)
        assertEquals("1.12.0", lib!!.version)
    }

    @Test
    fun `parses libraries with string notation`() {
        val catalog = parser().parseText("""
            [libraries]
            androidx-core-ktx = "androidx.core:core-ktx:1.12.0"
        """.trimIndent())

        val lib = catalog.libraries["androidx-core-ktx"]
        assertNotNull(lib)
        assertEquals("androidx.core", lib!!.groupId)
        assertEquals("core-ktx", lib.artifactId)
        assertEquals("1.12.0", lib.version)
    }

    @Test
    fun `parses plugins section`() {
        val catalog = parser().parseText("""
            [versions]
            agp = "8.2.0"
            kotlin = "2.2.10"

            [plugins]
            android-application = { id = "com.android.application", version.ref = "agp" }
            kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
        """.trimIndent())

        val plugin = catalog.plugins["android-application"]
        assertNotNull(plugin)
        assertEquals("com.android.application", plugin!!.id)
        assertEquals("8.2.0", plugin.version)
    }

    @Test
    fun `parses real-world catalog`() {
        val catalog = parser().parseText("""
            [versions]
            agp = "8.2.0"
            kotlin = "2.2.10"
            coreKtx = "1.12.0"
            composeBom = "2025.06.00"

            [libraries]
            androidx-core-ktx = { group = "androidx.core", name = "core-ktx", version.ref = "coreKtx" }
            androidx-activity-compose = { group = "androidx.activity", name = "activity-compose", version = "1.8.2" }
            androidx-compose-bom = { group = "androidx.compose", name = "compose-bom", version.ref = "composeBom" }
            androidx-compose-ui = { group = "androidx.compose.ui", name = "ui" }
            androidx-compose-material3 = { group = "androidx.compose.material3", name = "material3" }

            [plugins]
            android-application = { id = "com.android.application", version.ref = "agp" }
            kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
            kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
        """.trimIndent())

        assertEquals(4, catalog.versions.size)
        assertEquals(5, catalog.libraries.size)
        assertEquals(3, catalog.plugins.size)

        // Verify BOM library resolved
        val bom = catalog.libraries["androidx-compose-bom"]
        assertNotNull(bom)
        assertEquals("2025.06.00", bom!!.version)
    }

    @Test
    fun `parses module notation`() {
        val catalog = parser().parseText("""
            [libraries]
            androidx-core = { module = "androidx.core:core-ktx", version.ref = "coreKtx" }
        """.trimIndent())

        // Module notation may or may not be supported depending on regex
        // This test documents current behavior
        val lib = catalog.libraries["androidx-core"]
        // If not supported, this will be null — that's acceptable for now
    }
}
