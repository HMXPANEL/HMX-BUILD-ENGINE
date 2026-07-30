package com.hbe.dex

import com.hbe.api.*
import com.hbe.api.exception.DexException
import java.nio.file.Path

class DexEngineImpl(
    private val sdkManager: SdkManager,
    private val fileSystem: FileSystem,
    private val processRunner: ProcessRunner,
    private val logger: Logger
) : DexEngine {

    override fun dex(classFiles: Set<Path>, config: DexConfig): DexOutput {
        if (classFiles.isEmpty()) {
            throw DexException("No class files to dex")
        }

        fileSystem.createDirectories(config.outputDir)
        logger.info("DEX generation not yet implemented — will invoke d8",
            mapOf("classCount" to classFiles.size.toString()))

        return DexOutput(
            dexFiles = listOf(config.outputDir.resolve("classes.dex")),
            totalMethodCount = 0,
            dexFileCount = 1
        )
    }

    override fun r8(classFiles: Set<Path>, config: DexConfig, proguardRules: Path?): DexOutput {
        logger.info("R8 optimization not yet implemented",
            mapOf("classCount" to classFiles.size.toString()))
        return dex(classFiles, config)
    }

    override fun computeMethodCount(classFiles: Set<Path>): Int {
        // Will be implemented with actual method counting via d8 --print-method-count
        return 0
    }

    override fun buildMainDexList(classFiles: Set<Path>, config: DexConfig): List<Path> {
        if (classFiles.isEmpty()) return emptyList()

        // Main dex heuristic: sort by package name to prioritize application/activity classes
        val sorted = classFiles.sortedBy { it.toString() }
        val mainDexCount = kotlin.math.max(1, classFiles.size / 5)
        return sorted.take(mainDexCount)
    }
}
