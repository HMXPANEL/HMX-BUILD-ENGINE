package com.hbe.resources

import com.hbe.api.*
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ResourceCompilerImplTest {

    @TempDir
    lateinit var tempDir: Path

    private val logger = com.hbe.core.DefaultLogger()
    private val sdkManager = mockk<SdkManager>()

    private fun writeManifest(packageName: String = "com.hbe.testapp"): Path {
        val manifest = tempDir.resolve("AndroidManifest.xml")
        Files.writeString(manifest, """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                package="$packageName">
                <uses-sdk android:minSdkVersion="24" android:targetSdkVersion="34"/>
                <application android:label="@string/app_name">
                    <activity android:name=".MainActivity" android:exported="true"/>
                </application>
            </manifest>
        """.trimIndent())
        return manifest
    }

    private fun compiler(toolRunner: ToolRunner): ResourceCompilerImpl {
        every { sdkManager.resolveSdk(35) } returns SdkResolution(
            sdkRoot = tempDir.resolve("sdk"),
            platformDir = tempDir.resolve("sdk/platforms/android-35"),
            buildToolsDir = tempDir.resolve("sdk/build-tools/35.0.0"),
            jdkHome = tempDir.resolve("jdk")
        )
        return ResourceCompilerImpl(sdkManager, com.hbe.infra.OsFileSystem(), toolRunner, logger)
    }

    private fun writeZip(target: Path) {
        Files.createDirectories(target.parent)
        ZipOutputStream(Files.newOutputStream(target)).use { zos ->
            zos.putNextEntry(ZipEntry("AndroidManifest.xml"))
            zos.write("<manifest/>".toByteArray())
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("resources.arsc"))
            zos.write("arsc-bytes".toByteArray())
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("res/layout/activity_main.xml"))
            zos.write("<layout/>".toByteArray())
            zos.closeEntry()
        }
    }

    private fun mockToolRunner(): ToolRunner {
        val toolRunner = mockk<ToolRunner>()
        every { toolRunner.run("aapt2", any(), any()) } answers {
            val args = secondArg<List<String>>()
            if (args.first() == "compile") {
                val outputDir = Path.of(args[args.indexOf("-o") + 1])
                Files.createDirectories(outputDir)
                Files.writeString(outputDir.resolve("layout_activity_main.flat"), "compiled")
            } else {
                val outApk = Path.of(args[args.indexOf("-o") + 1])
                writeZip(outApk)
                val symbols = Path.of(args[args.indexOf("--output-text-symbols") + 1])
                Files.createDirectories(symbols.parent)
                Files.writeString(symbols, """
                    int layout activity_main 0x7f030000
                    int string app_name 0x7f040001
                    int[] styleable ActionBar { 0x7f050000 }
                """.trimIndent())
                val genDir = Path.of(args[args.indexOf("--java") + 1])
                val pkgDir = genDir.resolve("com/hbe/testapp")
                Files.createDirectories(pkgDir)
                Files.writeString(pkgDir.resolve("R.java"), "package com.hbe.testapp; public class R {}")
            }
            ToolResult(0, "", "", 10, true)
        }
        return toolRunner
    }

    @Test
    fun `parseManifest extracts package sdk levels and activities`() {
        val manifest = writeManifest()
        val info = compiler(mockk()).parseManifest(manifest)
        assertEquals("com.hbe.testapp", info.packageName)
        assertEquals(24, info.minSdk)
        assertEquals(34, info.targetSdk)
        assertEquals(listOf(".MainActivity"), info.activities)
    }

    @Test
    fun `discoverResources counts by resource type`() {
        val resDir = tempDir.resolve("res")
        Files.createDirectories(resDir.resolve("values"))
        Files.createDirectories(resDir.resolve("layout"))
        Files.createDirectories(resDir.resolve("drawable"))
        Files.writeString(resDir.resolve("values/strings.xml"), "<resources/>")
        Files.writeString(resDir.resolve("layout/activity_main.xml"), "<layout/>")
        Files.writeString(resDir.resolve("drawable/icon.png"), "png")

        val discovery = compiler(mockk()).discoverResources(resDir)

        assertEquals(3, discovery.fileCount)
        assertEquals(1, discovery.directories["values"])
        assertEquals(1, discovery.directories["layout"])
        assertEquals(1, discovery.directories["drawable"])
    }

    @Test
    fun `parseSymbols maps int entries and skips int arrays`() {
        val symbols = tempDir.resolve("symbols.txt")
        Files.writeString(symbols, """
            int layout activity_main 0x7f030000
            int string app_name 0x7f040001
            int[] styleable ActionBar { 0x7f050000 }
        """.trimIndent())

        val ids = compiler(mockk()).parseSymbols(symbols)

        assertEquals(2, ids.size)
        assertEquals(0x7f030000, ids["activity_main"])
        assertEquals(0x7f040001, ids["app_name"])
    }

    @Test
    fun `parseAapt2Errors extracts error lines`() {
        val stderr = """
            /res/values/strings.xml:3: error: <item> has an unexpected tag.
            warning: no resources found
            error: failed to parse file
        """.trimIndent()

        val errors = compiler(mockk()).parseAapt2Errors(stderr)

        assertEquals(2, errors.size)
        assertTrue(errors[0].contains("unexpected tag"))
    }

    @Test
    fun `compile invokes aapt2 and returns flat files`() {
        val toolRunner = mockToolRunner()
        val compiler = compiler(toolRunner)
        val resDir = tempDir.resolve("res")
        Files.createDirectories(resDir.resolve("values"))
        Files.writeString(resDir.resolve("values/strings.xml"), "<resources/>")
        val outDir = tempDir.resolve("flat")

        val flatFiles = compiler.compile(resDir, outDir)

        assertEquals(1, flatFiles.size)
        verify { toolRunner.run("aapt2", match { it.contains("compile") && it.contains("--dir") }, any()) }
    }

    @Test
    fun `link invokes aapt2 link and returns linked bundle`() {
        val toolRunner = mockToolRunner()
        val compiler = compiler(toolRunner)
        val manifest = writeManifest()

        val bundle = compiler.link(
            flatFiles = listOf(tempDir.resolve("flat/layout_activity_main.flat")),
            manifest = manifest,
            outputDir = tempDir.resolve("link"),
            compileSdk = 35
        )

        assertTrue(Files.isRegularFile(bundle.resourcesArsc))
        assertTrue(Files.isRegularFile(bundle.manifest))
        assertEquals(tempDir.resolve("link/gen/com/hbe/testapp/R.java"), bundle.rJava)
        assertEquals(2, bundle.resourceIds.size)
        assertEquals(0x7f030000, bundle.resourceIds["activity_main"])
        verify {
            toolRunner.run("aapt2", match {
                it.contains("link") && it.contains("-I") && it.contains("--manifest") &&
                    it.contains("--min-sdk-version") && it.contains("--target-sdk-version")
            }, any())
        }
    }

    @Test
    fun `link with failing aapt2 throws ResourceException`() {
        val toolRunner = mockk<ToolRunner>()
        every { toolRunner.run("aapt2", any(), any()) } returns ToolResult(
            1, "", "res/values/strings.xml:3: error: <item> has an unexpected tag.", 5, false
        )
        val compiler = compiler(toolRunner)
        val manifest = writeManifest()

        val ex = assertThrows(com.hbe.api.exception.ResourceException::class.java) {
            compiler.link(emptyList(), manifest, tempDir.resolve("link"), 35)
        }
        assertTrue(ex.details.any { it.contains("unexpected tag") })
    }
}
