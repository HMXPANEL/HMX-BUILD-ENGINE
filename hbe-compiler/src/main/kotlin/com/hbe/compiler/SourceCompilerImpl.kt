package com.hbe.compiler

import com.hbe.api.*
import com.hbe.api.exception.CompilerException
import com.hbe.api.exception.CompilerError
import java.nio.file.Path
import javax.tools.ToolProvider

class SourceCompilerImpl(
    private val sdkManager: SdkManager,
    private val fileSystem: FileSystem,
    private val toolRunner: ToolRunner,
    private val logger: Logger
) : SourceCompiler {

    override fun compileJava(sources: Set<Path>, classpath: Classpath, outputDir: Path): Set<Path> {
        if (sources.isEmpty()) return emptySet()

        fileSystem.createDirectories(outputDir)

        val compiler = ToolProvider.getSystemJavaCompiler()
        if (compiler != null) {
            return compileWithJdkApi(compiler, sources, classpath, outputDir)
        }

        logger.warn("JDK Compiler API unavailable, falling back to javac process")
        return compileWithJavac(sources, classpath, outputDir)
    }

    override fun compileKotlin(
        sources: Set<Path>,
        classpath: Classpath,
        outputDir: Path,
        useCompose: Boolean,
        kotlinVersion: String
    ): Set<Path> {
        if (sources.isEmpty()) return emptySet()

        fileSystem.createDirectories(outputDir)
        logger.info("Compiling Kotlin sources", mapOf(
            "count" to sources.size.toString(),
            "compose" to useCompose.toString(),
            "kotlinVersion" to kotlinVersion
        ))

        if (useCompose) {
            logger.warn("Compose compiler plugin not bundled — compiling without it",
                mapOf("suggestion" to "Add the Compose compiler plugin for composable code generation"))
        }

        val args = mutableListOf(
            "-d", outputDir.toString(),
            "-cp", classpath.toJvmClasspath(),
            "-jvm-target", "17"
        )
        args.addAll(sources.map { it.toString() })

        val result = toolRunner.run("kotlinc", args)
        if (!result.succeeded) {
            throw CompilerException(
                message = "Kotlin compilation failed",
                phase = "kotlinc",
                errors = parseKotlincErrors(result.stderr),
                suggestion = "Fix the compilation errors and rebuild"
            )
        }

        return fileSystem.walkFiles(outputDir, "*.class").toSet()
    }

    override fun batchCompileJava(
        allSources: Set<Path>,
        classpath: Classpath,
        outputDir: Path,
        batchSize: Int
    ): Set<Path> {
        if (allSources.isEmpty()) return emptySet()

        val classFiles = mutableSetOf<Path>()
        val batches = allSources.chunked(batchSize)

        for ((index, batch) in batches.withIndex()) {
            val batchOutput = outputDir.resolve("batch-$index")
            val batchClassFiles = compileJava(batch.toSet(), classpath, batchOutput)
            classFiles.addAll(batchClassFiles)
        }

        return classFiles
    }

    override fun batchCompileKotlin(
        allSources: Set<Path>,
        classpath: Classpath,
        outputDir: Path,
        useCompose: Boolean,
        batchSize: Int,
        kotlinVersion: String
    ): Set<Path> {
        if (allSources.isEmpty()) return emptySet()

        val classFiles = mutableSetOf<Path>()
        val batches = allSources.chunked(batchSize)

        for ((index, batch) in batches.withIndex()) {
            val batchOutput = outputDir.resolve("batch-$index")
            val batchClassFiles = compileKotlin(
                batch.toSet(), classpath, batchOutput, useCompose, kotlinVersion
            )
            classFiles.addAll(batchClassFiles)
        }

        return classFiles
    }

    private fun compileWithJdkApi(
        compiler: javax.tools.JavaCompiler,
        sources: Set<Path>,
        classpath: Classpath,
        outputDir: Path
    ): Set<Path> {
        val fileManager = compiler.getStandardFileManager(null, null, null)

        val compilationUnits = sources.map { path ->
            fileManager.getJavaFileObjects(path)
        }.flatMap { it.toList() }

        val options = mutableListOf(
            "-d", outputDir.toString(),
            "-classpath", classpath.toJvmClasspath(),
            "-source", "17",
            "-target", "17",
            "-Xlint:-options"
        )

        if (classpath.processorPath.isNotEmpty()) {
            options.add("-processorpath")
            options.add(classpath.processorPath.joinToString(System.getProperty("path.separator")))
        }

        val errors = mutableListOf<CompilerError>()
        val diagnosticListener = javax.tools.DiagnosticListener<javax.tools.JavaFileObject> { diagnostic ->
            if (diagnostic.kind == javax.tools.Diagnostic.Kind.ERROR) {
                errors.add(CompilerError(
                    file = diagnostic.source?.toUri()?.toString() ?: "unknown",
                    line = diagnostic.lineNumber.toInt(),
                    column = diagnostic.columnNumber.toInt(),
                    message = diagnostic.getMessage(null) ?: "Unknown error"
                ))
            }
        }

        val task = compiler.getTask(
            null,
            fileManager,
            diagnosticListener,
            options,
            null,
            compilationUnits
        )

        val success = task.call()

        if (!success) {
            throw CompilerException(
                message = "Java compilation failed with ${errors.size} error(s)",
                phase = "javac",
                errors = errors,
                suggestion = "Fix the compilation errors and rebuild"
            )
        }

        return fileSystem.walkFiles(outputDir, "*.class").toSet()
    }

    private fun compileWithJavac(
        sources: Set<Path>,
        classpath: Classpath,
        outputDir: Path
    ): Set<Path> {
        val args = mutableListOf(
            "-d", outputDir.toString(),
            "-cp", classpath.toJvmClasspath(),
            "-source", "17",
            "-target", "17"
        )
        args.addAll(sources.map { it.toString() })

        val result = toolRunner.run("javac", args)
        if (!result.succeeded) {
            throw CompilerException(
                message = "Java compilation failed",
                phase = "javac",
                errors = parseJavacErrors(result.stderr),
                suggestion = "Fix the compilation errors and rebuild"
            )
        }

        return fileSystem.walkFiles(outputDir, "*.class").toSet()
    }

    private fun parseJavacErrors(stderr: String): List<CompilerError> {
        return stderr.lines().filter { it.contains(".java:") }.mapNotNull { line ->
            val parts = line.split(":")
            if (parts.size >= 3) {
                CompilerError(
                    file = parts[0].trim(),
                    line = parts[1].trim().toIntOrNull() ?: 0,
                    column = parts.getOrNull(2)?.trim()?.toIntOrNull() ?: 0,
                    message = parts.drop(3).joinToString(":").trim()
                )
            } else null
        }
    }

    private fun parseKotlincErrors(stderr: String): List<CompilerError> {
        return stderr.lines().filter { it.contains(".kt:") }.mapNotNull { line ->
            val marker = line.indexOf(".kt:")
            if (marker >= 0) {
                val file = line.substring(0, marker + 3)
                val after = line.substring(marker + 4).split(":")
                CompilerError(
                    file = file.trim(),
                    line = after.getOrNull(0)?.trim()?.toIntOrNull() ?: 0,
                    column = after.getOrNull(1)?.trim()?.toIntOrNull() ?: 0,
                    message = after.drop(2).joinToString(":").trim()
                )
            } else null
        }
    }
}
