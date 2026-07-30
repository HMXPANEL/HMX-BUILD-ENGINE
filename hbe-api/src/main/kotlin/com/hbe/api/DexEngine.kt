package com.hbe.api

import java.nio.file.Path

interface DexEngine {
    fun dex(classFiles: Set<Path>, config: DexConfig): DexOutput
    fun r8(classFiles: Set<Path>, config: DexConfig, proguardRules: Path? = null): DexOutput
    fun computeMethodCount(classFiles: Set<Path>): Int
    fun buildMainDexList(classFiles: Set<Path>, config: DexConfig): List<Path>
}

data class DexConfig(
    val minSdk: Int = 24,
    val debug: Boolean = true,
    val outputDir: Path,
    val libraryJars: List<Path> = emptyList(),
    val mainDexList: Path? = null,
    val isRelease: Boolean = false
)

data class DexOutput(
    val dexFiles: List<Path>,
    val totalMethodCount: Int = 0,
    val totalFieldCount: Int = 0,
    val dexFileCount: Int = 1,
    val mappingFile: Path? = null,
    val isOptimized: Boolean = false
)
