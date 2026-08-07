package com.hbe.resources

import com.hbe.api.*
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Standalone merge tests that avoid mockk (which fails to initialize under
 * PRoot/ByteBuddyAgent). mergeResources only touches fileSystem, so the other
 * constructor args are no-op stubs.
 */
class ResourceMergeTest {

    @TempDir
    lateinit var tempDir: Path

    private class NoopLogger : Logger {
        override fun debug(message: String, context: Map<String, Any>) {}
        override fun info(message: String, context: Map<String, Any>) {}
        override fun warn(message: String, context: Map<String, Any>) {}
        override fun error(message: String, context: Map<String, Any>) {}
        override fun trace(message: String, context: Map<String, Any>) {}
        override fun setLevel(level: Logger.LogLevel) {}
        override fun setOutput(output: Logger.LogOutput) {}
    }

    private class NoopToolRunner : ToolRunner {
        override fun run(tool: String, args: List<String>, options: ToolOptions): ToolResult =
            ToolResult(0, "", "", 0, true)
    }

    private val fakeSdkRoot = Path.of("/fake/sdk")

    private inner class NoopSdk : SdkManager {
        override fun resolveSdk(compileSdk: Int, buildToolsVersion: String?): SdkResolution = SdkResolution(
            fakeSdkRoot, fakeSdkRoot.resolve("p"), fakeSdkRoot.resolve("bt"), jdkHome = fakeSdkRoot.resolve("jdk")
        )
        override fun doctor(): SdkDiagnosis = SdkDiagnosis(false, false)
        override fun downloadPlatform(apiLevel: Int) {}
        override fun downloadBuildTools(version: String) {}
        override fun getSdkPath(): Path? = fakeSdkRoot
        override fun getJdkPath(): Path = fakeSdkRoot.resolve("jdk")
        override fun getToolPath(toolName: String): Path = fakeSdkRoot.resolve("bt/$toolName")
        override fun findTool(toolName: String): Path? = null
        override fun listInstalledSdk(): SdkEnvironment = SdkEnvironment(null, emptyList(), emptyList(), false, false)
        override fun listInstalledTools(): List<ToolInfo> = emptyList()
        override fun validateEnvironment(): EnvironmentReport = EnvironmentReport(
            "linux", "aarch64", "24", true, false,
            ToolInfo("java", "Java", "17", fakeSdkRoot.resolve("jdk"), true), null, emptyMap(), emptyList(), emptyList()
        )
    }

    private fun makeCompiler(): ResourceCompilerImpl =
        ResourceCompilerImpl(NoopSdk(), com.hbe.infra.OsFileSystem(), NoopToolRunner(), NoopLogger())

    private fun makeResDir(values: List<Pair<String, String>>): Path {
        val res = tempDir.resolve("res-${java.util.concurrent.ThreadLocalRandom.current().nextInt()}")
        val valuesDir = res.resolve("values")
        values.forEach { (name, content) ->
            Files.createDirectories(valuesDir)
            Files.writeString(valuesDir.resolve(name), content)
        }
        return res
    }

    @Test
    fun `merge preserves xliff namespace`() {
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
        val out = tempDir.resolve("merged")
        makeCompiler().mergeResources(out, app, listOf(lib))
        val merged = Files.readString(out.resolve("values/values.xml"))
        assertTrue(merged.contains("xmlns:ns1=\"urn:oasis:names:tc:xliff:document:1.2\""), "xliff ns missing: $merged")
        assertTrue(merged.contains("app_text"), "app_text missing")
        assertTrue(merged.contains("lib_text"), "lib_text missing")
    }

    @Test
    fun `merge preserves tools namespace`() {
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
        val out = tempDir.resolve("merged")
        makeCompiler().mergeResources(out, app, listOf(lib))
        val merged = Files.readString(out.resolve("values/values.xml"))
        assertTrue(merged.contains("xmlns:tools=\"http://schemas.android.com/tools\""), "tools ns missing: $merged")
    }

    @Test
    fun `merge preserves android namespace`() {
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
        val out = tempDir.resolve("merged")
        makeCompiler().mergeResources(out, app, listOf(lib))
        val merged = Files.readString(out.resolve("values/values.xml"))
        assertTrue(merged.contains("xmlns:android=\"http://schemas.android.com/apk/res/android\""), "android ns missing: $merged")
    }

    @Test
    fun `merge preserves custom namespace`() {
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
        val out = tempDir.resolve("merged")
        makeCompiler().mergeResources(out, app, listOf(lib))
        val merged = Files.readString(out.resolve("values/values.xml"))
        assertTrue(merged.contains("xmlns:app=\"http://schemas.android.com/apk/res-auto\""), "app ns missing: $merged")
    }

    @Test
    fun `merged output is byte-valid re-parseable XML`() {
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
        val out = tempDir.resolve("merged")
        makeCompiler().mergeResources(out, app, listOf(lib))
        val bytes = Files.readAllBytes(out.resolve("values/values.xml"))
        val parsed = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(java.io.ByteArrayInputStream(bytes))
        assertNotNull(parsed.documentElement)
        assertEquals("resources", parsed.documentElement.tagName)
    }

    @Test
    fun `merge deduplicates by name keeping app value`() {
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
        val out = tempDir.resolve("merged")
        makeCompiler().mergeResources(out, app, listOf(lib))
        val merged = Files.readString(out.resolve("values/values.xml"))
        assertTrue(merged.contains("app value"), "app value must win")
        assertFalse(merged.contains("library value"), "library value must be dropped")
    }

    @Test
    fun `merge handles prefix collision across different URIs`() {
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
        val out = tempDir.resolve("merged")
        makeCompiler().mergeResources(out, app, listOf(lib1, lib2))
        val merged = Files.readString(out.resolve("values/values.xml"))
        assertTrue(merged.contains("from_first"), "first lib resource must survive")
        assertTrue(merged.contains("from_second"), "second lib resource must survive")
        assertTrue(merged.contains("http://example.com/first"), "first URI must be declared")
        assertTrue(merged.contains("http://example.com/second"), "second URI must be declared")
    }
}
