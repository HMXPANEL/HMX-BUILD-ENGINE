package com.hbe.packager

import com.hbe.api.DexOutput
import com.hbe.api.FileSystem
import com.hbe.api.Logger
import com.hbe.api.ResourceBundle
import io.mockk.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class PackagerImplTest {

    @TempDir
    lateinit var tempDir: Path

    private val logger = mockk<Logger>(relaxed = true)

    private fun packager(): PackagerImpl {
        return PackagerImpl(com.hbe.infra.OsFileSystem(), mockk(), logger)
    }

    @Test
    fun `packages stored entries with correct sizes and crc so the apk is re-readable`() {
        val outDir = tempDir.resolve("out")
        Files.createDirectories(outDir)
        val manifest = tempDir.resolve("AndroidManifest.xml")
        Files.writeString(manifest, "<manifest/>")
        val arsc = tempDir.resolve("resources.arsc")
        Files.write(arsc, ByteArray(128) { (it * 7).toByte() })
        val dex = tempDir.resolve("classes.dex")
        Files.write(dex, ByteArray(64) { 1 })

        val apk = packager().packageApk(
            dexOutput = DexOutput(dexFiles = listOf(dex)),
            resources = ResourceBundle(
                resourcesArsc = arsc,
                rJava = tempDir.resolve("R.java"),
                manifest = manifest,
                compiledResDirectories = emptyList()
            ),
            manifest = manifest,
            nativeLibs = emptyList(),
            assets = emptyList(),
            kotlinMetadataDir = null,
            outputDir = outDir
        )

        ZipFile(apk.toFile()).use { zf ->
            val entries = zf.entries().asSequence().map { it.name }.toList()
            assertTrue(entries.contains("AndroidManifest.xml"))
            assertTrue(entries.contains("classes.dex"))
            assertTrue(entries.contains("resources.arsc"))
            val arscEntry = zf.getEntry("resources.arsc")
            assertEquals(128, arscEntry.size)
            assertEquals(128, zf.getInputStream(arscEntry).use { it.readAllBytes().size })
        }
    }
}
