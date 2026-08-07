package com.hbe.resources
import io.mockk.*

import com.hbe.api.*
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

    private val logger = mockk<Logger>(relaxed = true)
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

    // --- mergeResources regression tests ---

    private fun writeValuesFile(dir: Path, name: String, content: String): Path {
        Files.createDirectories(dir)
        val file = dir.resolve(name)
        Files.writeString(file, content)
        return file
    }

    private fun makeResDir(
        values: List<Pair<String, String>>,
        extraDirs: Map<String, String> = emptyMap()
    ): Path {
        val res = tempDir.resolve("res-${java.util.concurrent.ThreadLocalRandom.current().nextInt()}")
        val valuesDir = res.resolve("values")
        for ((name, content) in values) {
            writeValuesFile(valuesDir, name, content)
        }
        for ((dirName, fileContent) in extraDirs) {
            val d = res.resolve(dirName)
            Files.createDirectories(d)
            Files.writeString(d.resolve("test.xml"), fileContent)
        }
        return res
    }

    @Test
    fun `mergeResources preserves xliff namespace declaration`() {
        val lib = makeResDir(listOf("strings.xml" to """
            <?xml version="1.0" encoding="utf-8"?>
            <resources xmlns:ns1="urn:oasis:names:tc:xliff:document:1.2">
                <string name="lib_text" ns1:someattr="x">Hello</string>
            </resources>
        """.trimIndent()))
        val app = makeResDir(listOf("strings.xml" to """
            <?xml version="1.0" encoding="utf-8"?>
            <resources>
                <string name="app_text">App</string>
            </resources>
        """.trimIndent()))

        val compiler = compiler(mockk())
        val outDir = tempDir.resolve("merged")
        compiler.mergeResources(outDir, app, listOf(lib))

        val merged = Files.readString(outDir.resolve("values.xml"))
        assertTrue(merged.contains("xmlns:ns1=\"urn:oasis:names:tc:xliff:document:1.2\""),
            "xliff namespace must be declared. Got: $merged")
        assertTrue(merged.contains("app_text"), "app resource must be present")
        assertTrue(merged.contains("lib_text"), "library resource must be present")
    }

    @Test
    fun `mergeResources preserves tools namespace declaration`() {
        val lib = makeResDir(listOf("values.xml" to """
            <?xml version="1.0" encoding="utf-8"?>
            <resources xmlns:tools="http://schemas.android.com/tools">
                <style name="Base" parent="Theme.AppCompat">
                    <item name="colorPrimary" tools:targetApi="21">#FFF</item>
                </style>
            </resources>
        """.trimIndent()))
        val app = makeResDir(listOf("values.xml" to """
            <?xml version="1.0" encoding="utf-8"?>
            <resources>
                <style name="AppTheme" parent="Base"/>
            </resources>
        """.trimIndent()))

        val compiler = compiler(mockk())
        val outDir = tempDir.resolve("merged-tools")
        compiler.mergeResources(outDir, app, listOf(lib))

        val merged = Files.readString(outDir.resolve("values.xml"))
        assertTrue(merged.contains("xmlns:tools=\"http://schemas.android.com/tools\""),
            "tools namespace must be declared. Got: $merged")
    }

    @Test
    fun `mergeResources preserves android namespace declaration`() {
        val lib = makeResDir(listOf("values.xml" to """
            <?xml version="1.0" encoding="utf-8"?>
            <resources xmlns:android="http://schemas.android.com/apk/res/android">
                <bool name="is_tablet">false</bool>
            </resources>
        """.trimIndent()))
        val app = makeResDir(listOf("values.xml" to """
            <?xml version="1.0" encoding="utf-8"?>
            <resources>
                <string name="app_name">Test</string>
            </resources>
        """.trimIndent()))

        val compiler = compiler(mockk())
        val outDir = tempDir.resolve("merged-android")
        compiler.mergeResources(outDir, app, listOf(lib))

        val merged = Files.readString(outDir.resolve("values.xml"))
        assertTrue(merged.contains("xmlns:android=\"http://schemas.android.com/apk/res/android\""),
            "android namespace must be declared. Got: $merged")
    }

    @Test
    fun `mergeResources preserves custom namespace declaration`() {
        val lib = makeResDir(listOf("values.xml" to """
            <?xml version="1.0" encoding="utf-8"?>
            <resources xmlns:app="http://schemas.android.com/apk/res-auto">
                <attr name="customAttr" format="color"/>
            </resources>
        """.trimIndent()))
        val app = makeResDir(listOf("values.xml" to """
            <?xml version="1.0" encoding="utf-8"?>
            <resources>
                <string name="hello">Hi</string>
            </resources>
        """.trimIndent()))

        val compiler = compiler(mockk())
        val outDir = tempDir.resolve("merged-custom")
        compiler.mergeResources(outDir, app, listOf(lib))

        val merged = Files.readString(outDir.resolve("values.xml"))
        assertTrue(merged.contains("xmlns:app=\"http://schemas.android.com/apk/res-auto\""),
            "custom app namespace must be declared. Got: $merged")
    }

    @Test
    fun `mergeResources produces byte-valid XML that re-parses cleanly`() {
        val lib = makeResDir(listOf("values.xml" to """
            <?xml version="1.0" encoding="utf-8"?>
            <resources xmlns:ns1="urn:oasis:names:tc:xliff:document:1.2">
                <string name="greeting">Hello</string>
                <color name="bg">#FFFFFF</color>
            </resources>
        """.trimIndent()))
        val app = makeResDir(listOf("values.xml" to """
            <?xml version="1.0" encoding="utf-8"?>
            <resources>
                <string name="app_name">MyApp</string>
            </resources>
        """.trimIndent()))

        val compiler = compiler(mockk())
        val outDir = tempDir.resolve("merged-valid")
        compiler.mergeResources(outDir, app, listOf(lib))

        val bytes = Files.readAllBytes(outDir.resolve("values.xml"))
        val parsed = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(java.io.ByteArrayInputStream(bytes))
        assertNotNull(parsed.documentElement, "merged XML must re-parse without error")
        assertEquals("resources", parsed.documentElement.tagName)
    }

    @Test
    fun `mergeResources deduplicates by resource name keeping app value`() {
        val lib = makeResDir(listOf("strings.xml" to """
            <?xml version="1.0" encoding="utf-8"?>
            <resources>
                <string name="shared_key">library value</string>
            </resources>
        """.trimIndent()))
        val app = makeResDir(listOf("strings.xml" to """
            <?xml version="1.0" encoding="utf-8"?>
            <resources>
                <string name="shared_key">app value</string>
            </resources>
        """.trimIndent()))

        val compiler = compiler(mockk())
        val outDir = tempDir.resolve("merged-dedup")
        compiler.mergeResources(outDir, app, listOf(lib))

        val merged = Files.readString(outDir.resolve("values.xml"))
        assertTrue(merged.contains("app value"), "app value must win on conflict")
        assertFalse(merged.contains("library value"), "library value must be dropped on conflict")
    }

    @Test
    fun `mergeResources handles prefix collision across different URIs`() {
        // Two libraries both use prefix "ns1" for DIFFERENT URIs — must not drop either.
        val lib1 = makeResDir(listOf("values.xml" to """
            <?xml version="1.0" encoding="utf-8"?>
            <resources xmlns:ns1="http://example.com/first">
                <string name="from_first">A</string>
            </resources>
        """.trimIndent()))
        val lib2 = makeResDir(listOf("values.xml" to """
            <?xml version="1.0" encoding="utf-8"?>
            <resources xmlns:ns1="http://example.com/second">
                <string name="from_second">B</string>
            </resources>
        """.trimIndent()))
        val app = makeResDir(listOf("values.xml" to """
            <?xml version="1.0" encoding="utf-8"?>
            <resources>
                <string name="app_val">C</string>
            </resources>
        """.trimIndent()))

        val compiler = compiler(mockk())
        val outDir = tempDir.resolve("merged-collision")
        compiler.mergeResources(outDir, app, listOf(lib1, lib2))

        val merged = Files.readString(outDir.resolve("values.xml"))
        assertTrue(merged.contains("from_first"), "first lib resource must survive")
        assertTrue(merged.contains("from_second"), "second lib resource must survive")
        // Both URIs must be declared (one with a generated unique prefix)
        assertTrue(merged.contains("http://example.com/first"), "first URI must be declared")
        assertTrue(merged.contains("http://example.com/second"), "second URI must be declared")
    }
}
