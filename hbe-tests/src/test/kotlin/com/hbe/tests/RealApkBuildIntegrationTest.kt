package com.hbe.tests

import com.hbe.api.EngineConfig
import com.hbe.api.dto.BuildRequest
import com.hbe.api.dto.BuildResult
import com.hbe.core.BuildContextImpl
import com.hbe.core.DefaultLogger
import com.hbe.core.event.EventEmittingToolRunner
import com.hbe.core.event.InMemoryBuildEventBus
import com.hbe.core.pipeline.DefaultBuildPipeline
import com.hbe.infra.OsFileSystem
import com.hbe.infra.OsProcessRunner
import com.hbe.sdk.SdkManagerImpl
import com.hbe.sdk.ToolRunnerImpl
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipInputStream
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class RealApkBuildIntegrationTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `builds and signs a minimal APK end to end`() {
        val logger = DefaultLogger()
        val fileSystem = OsFileSystem()
        val processRunner = OsProcessRunner()
        val sdkManager = SdkManagerImpl(fileSystem, processRunner, logger)

        assumeTrue(sdkManager.listInstalledSdk().sdkRoot != null, "Android SDK required")
        assumeTrue(sdkManager.findTool("aapt2") != null, "aapt2 required")
        assumeTrue(sdkManager.findTool("d8") != null, "d8 required")

        val projectDir = createMinimalProject()
        val outputApk = tempDir.resolve("out/app-signed.apk")

        val eventBus = InMemoryBuildEventBus()
        val events = mutableListOf<com.hbe.api.event.BuildEvent>()
        eventBus.subscribe { events.add(it) }

        val toolRunner = EventEmittingToolRunner(
            ToolRunnerImpl(sdkManager, processRunner, logger), eventBus
        )

        val pipeline = DefaultBuildPipeline(
            sdkManager = sdkManager,
            resourceCompiler = com.hbe.resources.ResourceCompilerImpl(sdkManager, fileSystem, toolRunner, logger),
            sourceCompiler = com.hbe.compiler.SourceCompilerImpl(sdkManager, fileSystem, toolRunner, logger),
            dexEngine = com.hbe.dex.DexEngineImpl(sdkManager, fileSystem, toolRunner, logger),
            packager = com.hbe.packager.PackagerImpl(fileSystem, toolRunner, logger),
            signer = com.hbe.signer.SignerImpl(fileSystem, toolRunner, logger),
            toolRunner = toolRunner,
            fileSystem = fileSystem,
            eventBus = eventBus,
            logger = logger
        )

        val context = BuildContextImpl(
            buildId = "bld-it",
            request = BuildRequest(projectDir = projectDir.toString(), outputApkPath = outputApk.toString()),
            config = EngineConfig(autoDownloadSdk = false)
        )

        val result = pipeline.execute(context)

        assertEquals(BuildResult.Status.SUCCESS, result.status, "error: ${result.error?.message}")
        assertTrue(Files.isRegularFile(outputApk), "APK not produced at $outputApk")
        assertTrue(Files.size(outputApk) > 0)

        val entries = listZipEntries(outputApk)
        assertTrue(entries.contains("AndroidManifest.xml"), "Missing AndroidManifest.xml in APK")
        assertTrue(entries.contains("classes.dex"), "Missing classes.dex in APK")
        assertTrue(entries.contains("resources.arsc"), "Missing resources.arsc in APK")
        assertTrue(entries.any { it.startsWith("res/") }, "Missing compiled res/ entries in APK")

        val signature = com.hbe.signer.SignerImpl(fileSystem, toolRunner, logger).verify(outputApk)
        assertTrue(signature.isSigned, "APK was not signed")

        val eventTypes = events.map { it::class.simpleName }
        assertTrue(eventTypes.contains("ResourceCompilationStartedEvent"))
        assertTrue(eventTypes.contains("JavaCompilationStartedEvent"))
        assertTrue(eventTypes.contains("DexGenerationStartedEvent"))
        assertTrue(eventTypes.contains("PackagingStartedEvent"))
        assertTrue(eventTypes.contains("SigningStartedEvent"))
        assertTrue(eventTypes.contains("BuildFinishedEvent"))
    }

    private fun createMinimalProject(): Path {
        val projectDir = tempDir.resolve("testapp")
        val srcDir = projectDir.resolve("src/main/java/com/hbe/testapp")
        Files.createDirectories(srcDir)
        Files.createDirectories(projectDir.resolve("res/values"))
        Files.createDirectories(projectDir.resolve("res/layout"))

        Files.writeString(projectDir.resolve("AndroidManifest.xml"), """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                package="com.hbe.testapp">
                <uses-sdk android:minSdkVersion="24" android:targetSdkVersion="34"/>
                <application android:label="@string/app_name">
                    <activity android:name=".MainActivity" android:exported="true">
                        <intent-filter>
                            <action android:name="android.intent.action.MAIN"/>
                            <category android:name="android.intent.category.LAUNCHER"/>
                        </intent-filter>
                    </activity>
                </application>
            </manifest>
        """.trimIndent())

        Files.writeString(projectDir.resolve("res/values/strings.xml"), """
            <?xml version="1.0" encoding="utf-8"?>
            <resources>
                <string name="app_name">HBE Test App</string>
            </resources>
        """.trimIndent())

        Files.writeString(projectDir.resolve("res/layout/activity_main.xml"), """
            <?xml version="1.0" encoding="utf-8"?>
            <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
                android:layout_width="match_parent"
                android:layout_height="match_parent">
                <TextView
                    android:id="@+id/text"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:text="@string/app_name" />
            </LinearLayout>
        """.trimIndent())

        Files.writeString(srcDir.resolve("MainActivity.java"), """
            package com.hbe.testapp;

            import android.app.Activity;
            import android.os.Bundle;

            public class MainActivity extends Activity {
                @Override
                protected void onCreate(Bundle savedInstanceState) {
                    super.onCreate(savedInstanceState);
                    setContentView(R.layout.activity_main);
                }
            }
        """.trimIndent())

        return projectDir
    }

    private fun listZipEntries(apk: Path): List<String> {
        val entries = mutableListOf<String>()
        ZipInputStream(Files.newInputStream(apk)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                entries.add(entry.name)
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        return entries
    }
}
