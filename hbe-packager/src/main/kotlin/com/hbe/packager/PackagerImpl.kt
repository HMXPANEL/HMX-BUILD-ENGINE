package com.hbe.packager

import com.hbe.api.*
import com.hbe.api.exception.BuildException
import java.io.BufferedOutputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PackagerImpl(
    private val fileSystem: FileSystem,
    private val toolRunner: ToolRunner,
    private val logger: Logger
) : Packager {

    override fun packageApk(
        dexOutput: DexOutput,
        resources: ResourceBundle,
        manifest: Path,
        nativeLibs: List<Path>,
        assets: List<Path>,
        kotlinMetadataDir: Path?,
        outputDir: Path
    ): Path {
        val apkFile = outputDir.resolve("app.apk")
        fileSystem.createDirectories(outputDir)

        val apkPath = apkFile.toString()
        ZipOutputStream(BufferedOutputStream(FileOutputStream(apkPath))).use { zos ->
            zos.setLevel(Deflater.DEFAULT_COMPRESSION)

            // 1. AndroidManifest.xml — first entry (required for alignment)
            addZipEntry(zos, "AndroidManifest.xml", manifest, Deflater.DEFLATED)

            // 2. classes.dex — stored (no compression)
            for ((index, dexFile) in dexOutput.dexFiles.withIndex()) {
                val entryName = if (index == 0) "classes.dex" else "classes${index + 1}.dex"
                addZipEntry(zos, entryName, dexFile, Deflater.NO_COMPRESSION)
            }

            // 3. resources.arsc — stored, must be 4-byte aligned
            addZipEntry(zos, "resources.arsc", resources.resourcesArsc, Deflater.NO_COMPRESSION)

            // 4. res/ directory
            for (resDir in resources.compiledResDirectories) {
                if (Files.isDirectory(resDir)) {
                    Files.walk(resDir).filter { Files.isRegularFile(it) }.forEach { file ->
                        val relativePath = resDir.relativize(file).toString()
                        addZipEntry(zos, "res/$relativePath", file, Deflater.DEFLATED)
                    }
                }
            }

            // 5. assets/
            for (assetPath in assets) {
                val entryName = "assets/${assetPath.fileName}"
                addZipEntry(zos, entryName, assetPath, Deflater.DEFLATED)
            }

            // 6. native libs — stored (no compression)
            for (lib in nativeLibs) {
                val abi = detectAbi(lib)
                val entryName = "lib/$abi/${lib.fileName}"
                addZipEntry(zos, entryName, lib, Deflater.NO_COMPRESSION)
            }
        }

        logger.info("APK packaged", mapOf("path" to apkPath, "size" to fileSystem.size(apkFile).toString()))
        return apkFile
    }

    override fun zipalign(apkFile: Path): Path {
        val alignedFile = apkFile.parent.resolve(apkFile.fileName.toString().replace(".apk", "-aligned.apk"))
        if (fileSystem.exists(alignedFile)) {
            fileSystem.delete(alignedFile)
        }

        val result = toolRunner.run("zipalign", listOf("-f", "4", apkFile.toString(), alignedFile.toString()))
        if (!result.succeeded) {
            throw BuildException(
                message = "zipalign failed",
                errorCode = "ZIPALIGN_ERROR",
                suggestion = "Ensure the APK is a valid zip with 4-byte aligned uncompressed entries",
                details = result.stderr.lines().filter { it.isNotBlank() }.take(10)
            )
        }

        logger.info("APK aligned", mapOf("path" to alignedFile.toString()))
        return alignedFile
    }

    private fun addZipEntry(zos: ZipOutputStream, name: String, source: Path, method: Int) {
        if (!Files.isRegularFile(source)) {
            logger.warn("Skipping missing file for APK entry", mapOf("entry" to name))
            return
        }

        val entry = ZipEntry(name).apply {
            this.method = method
            if (method == Deflater.NO_COMPRESSION) {
                val size = Files.size(source)
                this.size = size
                this.compressedSize = size
                this.crc = crc32(source)
            }
        }
        zos.putNextEntry(entry)
        Files.newInputStream(source).use { it.transferTo(zos) }
        zos.closeEntry()
    }

    private fun crc32(source: Path): Long {
        val crc = CRC32()
        Files.newInputStream(source).use { ins ->
            val buf = ByteArray(8192)
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                crc.update(buf, 0, n)
            }
        }
        return crc.value
    }

    private fun detectAbi(libPath: Path): String {
        val name = libPath.fileName.toString()
        return when {
            name.contains("arm64") || name.contains("aarch64") -> "arm64-v8a"
            name.contains("armeabi") || name.contains("armv7") -> "armeabi-v7a"
            name.contains("x86_64") -> "x86_64"
            name.contains("x86") -> "x86"
            else -> "arm64-v8a"
        }
    }
}
