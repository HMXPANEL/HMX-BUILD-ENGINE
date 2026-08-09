package com.hbe.compiler

import com.hbe.api.FileSystem
import com.hbe.api.Logger
import java.nio.file.Path

/**
 * Detects and provides access to the Kotlin compiler (kotlinc).
 *
 * Search order:
 * 1. ~/.hbe/kotlin/<version>/bin/kotlinc (HMX-managed)
 * 2. System kotlinc in PATH
 */
class KotlinToolchain(
    private val fileSystem: FileSystem,
    private val logger: Logger
) {
    /**
     * Locate the kotlinc executable for the given version.
     * Returns the path to the executable, or null if not found.
     */
    fun findKotlinc(kotlinVersion: String): Path? {
        // 1. HMX-managed Kotlin compiler
        val hbeKotlin = Path.of(
            System.getProperty("user.home") ?: ".",
            ".hbe", "kotlin", "kotlinc-$kotlinVersion", "bin", "kotlinc"
        )
        if (fileSystem.exists(hbeKotlin) && java.nio.file.Files.isExecutable(hbeKotlin)) {
            logger.info("Found HMX-managed Kotlin compiler", mapOf("path" to hbeKotlin.toString()))
            return hbeKotlin
        }
        // Also try without version qualifier (e.g. "kotlinc" dir)
        val hbeKotlinShort = Path.of(
            System.getProperty("user.home") ?: ".",
            ".hbe", "kotlin", "kotlinc", "bin", "kotlinc"
        )
        if (fileSystem.exists(hbeKotlinShort) && java.nio.file.Files.isExecutable(hbeKotlinShort)) {
            logger.info("Found HMX-managed Kotlin compiler", mapOf("path" to hbeKotlinShort.toString()))
            return hbeKotlinShort
        }
        // 2. System kotlinc (in PATH)
        val systemKotlinc = findInPath("kotlinc")
        if (systemKotlinc != null) {
            logger.info("Found system Kotlin compiler", mapOf("path" to systemKotlinc.toString()))
            return systemKotlinc
        }
        return null
    }

    /**
     * Get the Kotlin standard library JAR path for classpath inclusion.
     */
    fun findKotlinStdlib(kotlinVersion: String): Path? {
        val candidates = listOf(
            Path.of(System.getProperty("user.home") ?: ".", ".hbe", "kotlin", "kotlinc-$kotlinVersion", "lib", "kotlin-stdlib.jar"),
            Path.of(System.getProperty("user.home") ?: ".", ".hbe", "kotlin", "kotlinc", "lib", "kotlin-stdlib.jar")
        )
        for (c in candidates) {
            if (fileSystem.exists(c)) return c
        }
        return null
    }

    /**
     * Get the Compose compiler plugin JAR path (ships with Kotlin 2.x).
     */
    fun findComposeCompilerPlugin(kotlinVersion: String): Path? {
        val candidates = listOf(
            Path.of(System.getProperty("user.home") ?: ".", ".hbe", "kotlin", "kotlinc-$kotlinVersion", "lib"),
            Path.of(System.getProperty("user.home") ?: ".", ".hbe", "kotlin", "kotlinc", "lib")
        )
        for (libDir in candidates) {
            if (!fileSystem.exists(libDir)) continue
            // Compose compiler plugin is in the lib dir as a JAR
            val jars = runCatching {
                java.nio.file.Files.list(libDir)
                    .filter { it.fileName.toString().contains("compose-compiler") || it.fileName.toString().contains("compose-compiler-embeddable") }
                    .findFirst().orElse(null)
            }.getOrNull()
            if (jars != null) return jars
        }
        return null
    }

    /**
     * Find Compose runtime JAR in the Maven dependency cache.
     * Compose runtime must be on the classpath for compilation.
     */
    fun findComposeRuntime(): Path? {
        val cacheRoot = Path.of(System.getProperty("user.home") ?: ".", ".hbe", "dependencies")
        if (!fileSystem.exists(cacheRoot)) return null
        // Look for androidx compose runtime JAR in cache
        val candidates = listOf(
            "androidx/compose/runtime",
            "androidx.compose.runtime"
        )
        for (rel in candidates) {
            val dir = cacheRoot.resolve(rel)
            if (!fileSystem.exists(dir)) continue
            val jars = runCatching {
                java.nio.file.Files.list(dir)
                    .flatMap { verDir -> runCatching { java.nio.file.Files.list(verDir).filter { it.toString().endsWith(".jar") } }.getOrDefault(java.util.stream.Stream.empty()) }
                    .filter { it.fileName.toString().startsWith("runtime-") || it.fileName.toString().startsWith("runtime-") }
                    .findFirst().orElse(null)
            }.getOrNull()
            if (jars != null) return jars
        }
        return null
    }

    private fun findInPath(tool: String): Path? {
        val pathEnv = System.getenv("PATH") ?: return null
        for (dir in pathEnv.split(":")) {
            val p = Path.of(dir, tool)
            if (fileSystem.exists(p) && runCatching { java.nio.file.Files.isExecutable(p) }.getOrDefault(false)) {
                return p
            }
        }
        return null
    }
}
