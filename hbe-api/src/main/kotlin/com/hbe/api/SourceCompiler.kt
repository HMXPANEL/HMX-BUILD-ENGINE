package com.hbe.api

import java.nio.file.Path

interface SourceCompiler {
    fun compileJava(sources: Set<Path>, classpath: Classpath, outputDir: Path): Set<Path>
    fun compileKotlin(
        sources: Set<Path>,
        classpath: Classpath,
        outputDir: Path,
        useCompose: Boolean = false,
        kotlinVersion: String = "1.9.24"
    ): Set<Path>
    fun batchCompileJava(
        allSources: Set<Path>,
        classpath: Classpath,
        outputDir: Path,
        batchSize: Int = 50
    ): Set<Path>
    fun batchCompileKotlin(
        allSources: Set<Path>,
        classpath: Classpath,
        outputDir: Path,
        useCompose: Boolean = false,
        batchSize: Int = 30,
        kotlinVersion: String = "1.9.24"
    ): Set<Path>
}

data class Classpath(
    val entries: List<Path> = emptyList(),
    val processorPath: List<Path> = emptyList()
) {
    fun add(path: Path): Classpath = copy(entries = entries + path)
    fun addOutputDir(path: Path): Classpath = copy(entries = entries + path)
    fun addProcessor(path: Path): Classpath = copy(processorPath = processorPath + path)

    fun toJvmClasspath(): String = entries.joinToString(System.getProperty("path.separator")) { it.toString() }

    companion object {
        fun empty() = Classpath()
    }
}
