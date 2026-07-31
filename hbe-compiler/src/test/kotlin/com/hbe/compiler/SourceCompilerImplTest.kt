package com.hbe.compiler
import io.mockk.*

import com.hbe.api.Logger
import com.hbe.api.Classpath
import com.hbe.api.ToolResult
import com.hbe.api.ToolRunner
import com.hbe.api.exception.CompilerException
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class SourceCompilerImplTest {

    @TempDir
    lateinit var tempDir: Path

    private val logger = mockk<Logger>()

    private fun compiler(toolRunner: ToolRunner): SourceCompilerImpl {
        return SourceCompilerImpl(mockk(), com.hbe.infra.OsFileSystem(), toolRunner, logger)
    }

    @Test
    fun `compileKotlin invokes kotlinc and returns class files`() {
        val toolRunner = mockk<ToolRunner>()
        every { toolRunner.run("kotlinc", any(), any()) } answers {
            val args = secondArg<List<String>>()
            val outputDir = Path.of(args[args.indexOf("-d") + 1])
            Files.createDirectories(outputDir)
            Files.write(outputDir.resolve("MainKt.class"), byteArrayOf(1))
            ToolResult(0, "", "", 100, true)
        }
        val compiler = compiler(toolRunner)
        val source = tempDir.resolve("Main.kt")
        Files.write(source, "fun main() = println(\"hi\")".toByteArray())
        val classpath = Classpath(entries = listOf(tempDir.resolve("android.jar")))
        val outputDir = tempDir.resolve("classes")

        val classFiles = compiler.compileKotlin(setOf(source), classpath, outputDir)

        assertEquals(1, classFiles.size)
        verify {
            toolRunner.run("kotlinc", match {
                it.contains("-d") && it.contains("-jvm-target") && it.contains("17") &&
                    it.contains("-cp") && it.contains(classpath.toJvmClasspath()) &&
                    it.contains(source.toString())
            }, any())
        }
    }

    @Test
    fun `compileKotlin failure parses errors`() {
        val toolRunner = mockk<ToolRunner>()
        every { toolRunner.run("kotlinc", any(), any()) } returns ToolResult(
            1, "",
            "error: unresolved reference: foo\n" +
                "Main.kt:5:8: error: unresolved reference: foo\n" +
                "warning: some warning",
            100, false
        )
        val compiler = compiler(toolRunner)
        val source = tempDir.resolve("Main.kt")
        Files.write(source, "val x = foo".toByteArray())

        val ex = assertThrows(CompilerException::class.java) {
            compiler.compileKotlin(setOf(source), Classpath.empty(), tempDir.resolve("classes"))
        }

        assertEquals("kotlinc", ex.phase)
        assertEquals(1, ex.errors.size)
        assertEquals("Main.kt", ex.errors[0].file)
        assertEquals(5, ex.errors[0].line)
    }

    @Test
    fun `compileKotlin with no sources returns empty`() {
        val toolRunner = mockk<ToolRunner>()
        val classFiles = compiler(toolRunner).compileKotlin(emptySet(), Classpath.empty(), tempDir.resolve("classes"))
        assertTrue(classFiles.isEmpty())
    }
}
