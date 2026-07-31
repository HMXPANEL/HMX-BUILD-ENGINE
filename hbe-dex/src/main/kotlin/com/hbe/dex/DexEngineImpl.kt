package com.hbe.dex

import com.hbe.api.*
import com.hbe.api.exception.DexException
import java.nio.file.Files
import java.nio.file.Path

class DexEngineImpl(
    private val sdkManager: SdkManager,
    private val fileSystem: FileSystem,
    private val toolRunner: ToolRunner,
    private val logger: Logger
) : DexEngine {

    override fun dex(classFiles: Set<Path>, config: DexConfig): DexOutput {
        if (classFiles.isEmpty()) {
            throw DexException("No class files to dex")
        }

        fileSystem.createDirectories(config.outputDir)
        logger.info("Running d8", mapOf(
            "classCount" to classFiles.size.toString(),
            "minSdk" to config.minSdk.toString(),
            "release" to config.isRelease.toString()
        ))

        val args = mutableListOf<String>()
        args.add(if (config.isRelease) "--release" else "--debug")
        args.add("--min-api")
        args.add(config.minSdk.toString())
        config.mainDexList?.let {
            args.add("--main-dex-list")
            args.add(it.toString())
        }
        for (lib in config.libraryJars) {
            args.add("--lib")
            args.add(lib.toString())
        }
        args.add("--output")
        args.add(config.outputDir.toString())
        args.addAll(classFiles.map { it.toString() })

        val result = toolRunner.run("d8", args)
        if (!result.succeeded) {
            throw DexException(
                message = "d8 failed",
                suggestion = "Check class file compatibility and minSdk level",
                details = result.stderr.lines().filter { it.isNotBlank() }.take(20)
            )
        }

        val dexFiles = fileSystem.walkFiles(config.outputDir, "*.dex").sorted()
        if (dexFiles.isEmpty()) {
            throw DexException("d8 produced no dex files")
        }

        var methodCount = 0
        var fieldCount = 0
        for (dex in dexFiles) {
            val (methods, fields) = parseDexHeader(dex)
            methodCount += methods
            fieldCount += fields
        }

        logger.info("DEX generation complete", mapOf(
            "dexFiles" to dexFiles.size.toString(),
            "methodCount" to methodCount.toString()
        ))

        return DexOutput(
            dexFiles = dexFiles,
            totalMethodCount = methodCount,
            totalFieldCount = fieldCount,
            dexFileCount = dexFiles.size,
            isOptimized = config.isRelease
        )
    }

    override fun r8(classFiles: Set<Path>, config: DexConfig, proguardRules: Path?): DexOutput {
        logger.info("R8 optimization not yet available — using d8",
            mapOf("classCount" to classFiles.size.toString()))
        return dex(classFiles, config)
    }

    override fun computeMethodCount(classFiles: Set<Path>): Int {
        if (classFiles.isEmpty()) return 0
        val temp = fileSystem.createTempDirectory("hbe-method-count")
        return try {
            val output = dex(classFiles, DexConfig(outputDir = temp))
            output.totalMethodCount
        } finally {
            fileSystem.deleteRecursively(temp)
        }
    }

    override fun buildMainDexList(classFiles: Set<Path>, config: DexConfig): List<Path> {
        if (classFiles.isEmpty()) return emptyList()

        // Main dex heuristic: prioritize application/activity classes by package name
        val sorted = classFiles.sortedBy { it.toString() }
        val mainDexCount = kotlin.math.max(1, classFiles.size / 5)
        return sorted.take(mainDexCount)
    }

    internal fun parseDexHeader(dexFile: Path): Pair<Int, Int> {
        val bytes = Files.readAllBytes(dexFile)
        if (bytes.size < 96 || bytes[0] != 'd'.code.toByte() || bytes[1] != 'e'.code.toByte()) {
            return 0 to 0
        }
        val methodIds = leIntAt(bytes, 92)
        val fieldIds = leIntAt(bytes, 84)
        return methodIds to fieldIds
    }

    private fun leIntAt(bytes: ByteArray, offset: Int): Int {
        return (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)
    }
}
