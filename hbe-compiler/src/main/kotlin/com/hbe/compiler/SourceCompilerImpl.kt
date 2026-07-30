package com.hbe.compiler

import com.hbe.api.*
import com.hbe.api.exception.CompilerException
import com.hbe.api.exception.CompilerError
import java.nio.file.Path
import javax.tools.ToolProvider

class SourceCompilerImpl(
    private val sdkManager: SdkManager,
    private val fileSystem: FileSystem,
    private val processRunner: ProcessRunner,
    private val logger: Logger
) : SourceCompiler {

    override fun compileJava(sources: Set<Path>, classpath: Classpath, outputDir: Path): Set<Path> {
        if (sources.isEmpty()) return emptySet()

        fileSystem.createDirectories(outputDir)

        // Try JDK Compiler API first
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
        logger.info("Kotlin compilation not yet implemented — will invoke kotlinc",
            mapOf("count" to sources.size.toString(), "compose" to useCompose.toString()))
        return emptySet()
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
            val batchClassFiles = compileKotlin(
                batch.toSet(), classpath, outputDir, useCompose, kotlinVersion
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
        val javacPath = sdkManager.getJdkPath()?.resolve("bin")?.resolve("javac")
            ?: processRunner.findTool("javac")
            ?: throw CompilerException("javac not found", phase = "javac",
                suggestion = "Install JDK 17+ or set JAVA_HOME")

        val args = mutableListOf(
            "-d", outputDir.toString(),
            "-cp", classpath.toJvmClasspath(),
            "-source", "17",
            "-target", "17"
        )
        args.addAll(sources.map { it.toString() })

        val result = processRunner.run(javacPath.toString(), args)

        if (result.isFailure) {
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
}
