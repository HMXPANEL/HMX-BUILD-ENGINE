package com.hbe.core.project

import com.hbe.api.Logger
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Tests for [ManifestMerger] — verifies that library AAR manifests are correctly
 * merged into the app manifest, reproducing the Aira launch crash scenario.
 */
class ManifestMergerTest {

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

    private fun merger(): ManifestMerger =
        ManifestMerger(com.hbe.infra.OsFileSystem(), NoopLogger())

    private fun writeManifest(content: String): Path {
        val f = tempDir.resolve("manifest-${System.nanoTime()}.xml")
        Files.writeString(f, content)
        return f
    }

    private fun appManifest(applicationId: String = "com.test.app"): Path = writeManifest(
        """
        <?xml version="1.0" encoding="utf-8"?>
        <manifest xmlns:android="http://schemas.android.com/apk/res/android"
            package="$applicationId">
            <application android:label="TestApp">
                <activity android:name=".MainActivity" android:exported="true">
                    <intent-filter>
                        <action android:name="android.intent.action.MAIN" />
                        <category android:name="android.intent.category.LAUNCHER" />
                    </intent-filter>
                </activity>
            </application>
        </manifest>
        """.trimIndent()
    )

    private fun readMerged(merged: Path): String = Files.readString(merged)

    @Test
    fun `merges library provider into app manifest`() {
        val app = appManifest()
        val lib = writeManifest(
            """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                package="androidx.startup">
                <application>
                    <provider
                        android:name="androidx.startup.InitializationProvider"
                        android:authorities="${'$'}{applicationId}.androidx-startup"
                        android:exported="false">
                        <meta-data
                            android:name="androidx.emoji2.text.EmojiCompatInitializer"
                            android:value="androidx.startup" />
                    </provider>
                </application>
            </manifest>
            """.trimIndent()
        )

        val merged = merger().merge(app, listOf(lib), "com.test.app")
        val xml = readMerged(merged)

        assertTrue(xml.contains("androidx.startup.InitializationProvider"),
            "InitializationProvider must appear in merged manifest. Got:\n$xml")
        assertTrue(xml.contains("androidx-startup"), "authority placeholder must be substituted")
        assertFalse(xml.contains("\${applicationId}"), "no unreplaced placeholders")
    }

    @Test
    fun `reproduces aira failure - androidx startup provider missing causes crash`() {
        // This is the exact scenario: a library AAR contributes InitializationProvider.
        // Without merging, MainActivity (extends AppCompatActivity) crashes on launch
        // because ProcessLifecycleOwner and EmojiCompat are never initialized.
        val app = appManifest("com.hmx.aira")
        val coreKtx = writeManifest(
            """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                package="androidx.core">
                <application android:appComponentFactory="androidx.core.app.CoreComponentFactory" />
            </manifest>
            """.trimIndent()
        )
        val startup = writeManifest(
            """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                package="androidx.startup">
                <application>
                    <provider
                        android:name="androidx.startup.InitializationProvider"
                        android:authorities="${'$'}{applicationId}.androidx-startup"
                        android:exported="false">
                        <meta-data android:name="androidx.emoji2.text.EmojiCompatInitializer" android:value="androidx.startup" />
                        <meta-data android:name="androidx.lifecycle.ProcessLifecycleInitializer" android:value="androidx.startup" />
                        <meta-data android:name="androidx.profileinstaller.ProfileInstallerInitializer" android:value="androidx.startup" />
                    </provider>
                </application>
            </manifest>
            """.trimIndent()
        )

        val merged = merger().merge(app, listOf(coreKtx, startup), "com.hmx.aira")
        val xml = readMerged(merged)

        assertTrue(xml.contains("androidx.startup.InitializationProvider"), "provider missing from merged:\n$xml")
        assertTrue(xml.contains("androidx.core.app.CoreComponentFactory"), "appComponentFactory missing:\n$xml")
        assertTrue(xml.contains("com.hmx.aira.androidx-startup"), "authority not substituted:\n$xml")
    }

    @Test
    fun `merges library receiver with intent filters`() {
        val app = appManifest()
        val lib = writeManifest(
            """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                package="androidx.profileinstaller">
                <application>
                    <receiver
                        android:name="androidx.profileinstaller.ProfileInstallReceiver"
                        android:exported="true"
                        android:permission="android.permission.DUMP">
                        <intent-filter>
                            <action android:name="androidx.profileinstaller.action.INSTALL_PROFILE" />
                        </intent-filter>
                    </receiver>
                </application>
            </manifest>
            """.trimIndent()
        )

        val merged = merger().merge(app, listOf(lib), "com.test.app")
        val xml = readMerged(merged)

        assertTrue(xml.contains("ProfileInstallReceiver"), "receiver missing:\n$xml")
        assertTrue(xml.contains("INSTALL_PROFILE"), "intent-filter action missing:\n$xml")
        assertTrue(xml.contains("android.permission.DUMP"), "receiver permission missing:\n$xml")
    }

    @Test
    fun `merges library metadata into application`() {
        val app = appManifest()
        val lib = writeManifest(
            """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                package="com.example.lib">
                <application>
                    <meta-data android:name="com.example.API_KEY" android:value="12345" />
                </application>
            </manifest>
            """.trimIndent()
        )

        val merged = merger().merge(app, listOf(lib), "com.test.app")
        val xml = readMerged(merged)

        assertTrue(xml.contains("com.example.API_KEY"), "meta-data missing:\n$xml")
        assertTrue(xml.contains("12345"), "meta-data value missing:\n$xml")
    }

    @Test
    fun `merges application attributes from library when app does not set them`() {
        val app = appManifest()
        val lib = writeManifest(
            """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                package="androidx.core">
                <application
                    android:appComponentFactory="androidx.core.app.CoreComponentFactory"
                    android:allowBackup="false" />
            </manifest>
            """.trimIndent()
        )

        val merged = merger().merge(app, listOf(lib), "com.test.app")
        val xml = readMerged(merged)

        assertTrue(xml.contains("CoreComponentFactory"), "appComponentFactory should be merged:\n$xml")
        // app did not set allowBackup, so library's value fills in
        assertTrue(xml.contains("allowBackup=\"false\""), "library allowBackup should fill in:\n$xml")
    }

    @Test
    fun `does not override app attributes with library values`() {
        val app = writeManifest(
            """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                package="com.test.app">
                <application android:label="MyApp" android:allowBackup="true" />
            </manifest>
            """.trimIndent()
        )
        val lib = writeManifest(
            """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                package="com.example.lib">
                <application android:label="LibLabel" android:allowBackup="false" />
            </manifest>
            """.trimIndent()
        )

        val merged = merger().merge(app, listOf(lib), "com.test.app")
        val xml = readMerged(merged)

        assertTrue(xml.contains("MyApp"), "app label should be preserved:\n$xml")
        assertFalse(xml.contains("LibLabel"), "library label should not override:\n$xml")
    }

    @Test
    fun `deduplicates components by name`() {
        val app = appManifest()
        val lib = writeManifest(
            """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                package="com.example.lib">
                <application>
                    <activity android:name=".MainActivity" android:exported="false" />
                </application>
            </manifest>
            """.trimIndent()
        )

        val merged = merger().merge(app, listOf(lib), "com.test.app")
        val xml = readMerged(merged)

        // Only one MainActivity should exist (the app's, which is exported=true)
        val count = Regex("android:name=\"\\.MainActivity\"").findAll(xml).count()
        assertEquals(1, count, "should have exactly one MainActivity:\n$xml")
        assertTrue(xml.contains("exported=\"true\""), "app's exported=true should win:\n$xml")
    }

    @Test
    fun `substitutes applicationId placeholder`() {
        val app = appManifest()
        val lib = writeManifest(
            """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                package="androidx.startup">
                <application>
                    <provider
                        android:name="androidx.startup.InitializationProvider"
                        android:authorities="${'$'}{applicationId}.androidx-startup"
                        android:exported="false" />
                </application>
            </manifest>
            """.trimIndent()
        )

        val merged = merger().merge(app, listOf(lib), "com.hmx.aira")
        val xml = readMerged(merged)

        assertTrue(xml.contains("com.hmx.aira.androidx-startup"), "placeholder not substituted:\n$xml")
        assertFalse(xml.contains("\${applicationId}"), "unreplaced placeholder remains:\n$xml")
    }

    @Test
    fun `preserves namespace declarations from all manifests`() {
        val app = appManifest()
        val lib = writeManifest(
            """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                xmlns:tools="http://schemas.android.com/tools"
                package="com.example.lib">
                <application>
                    <activity android:name=".LibActivity" tools:ignore="MissingClass" />
                </application>
            </manifest>
            """.trimIndent()
        )

        val merged = merger().merge(app, listOf(lib), "com.test.app")
        val xml = readMerged(merged)

        assertTrue(xml.contains("xmlns:tools=\"http://schemas.android.com/tools\""),
            "tools namespace should be preserved:\n$xml")
        assertTrue(xml.contains("tools:ignore"), "tools:ignore attribute should be preserved:\n$xml")
    }

    @Test
    fun `merges multiple library manifests`() {
        val app = appManifest()
        val lib1 = writeManifest(
            """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                package="androidx.core">
                <application android:appComponentFactory="androidx.core.app.CoreComponentFactory" />
            </manifest>
            """.trimIndent()
        )
        val lib2 = writeManifest(
            """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                package="androidx.startup">
                <application>
                    <provider android:name="androidx.startup.InitializationProvider"
                        android:authorities="${'$'}{applicationId}.androidx-startup"
                        android:exported="false" />
                </application>
            </manifest>
            """.trimIndent()
        )
        val lib3 = writeManifest(
            """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                package="androidx.profileinstaller">
                <uses-permission android:name="android.permission.DUMP" />
                <application>
                    <receiver android:name="androidx.profileinstaller.ProfileInstallReceiver" android:exported="true" />
                </application>
            </manifest>
            """.trimIndent()
        )

        val merged = merger().merge(app, listOf(lib1, lib2, lib3), "com.test.app")
        val xml = readMerged(merged)

        assertTrue(xml.contains("CoreComponentFactory"), "missing from lib1:\n$xml")
        assertTrue(xml.contains("InitializationProvider"), "missing from lib2:\n$xml")
        assertTrue(xml.contains("ProfileInstallReceiver"), "missing from lib3:\n$xml")
        assertTrue(xml.contains("android.permission.DUMP"), "permission missing from lib3:\n$xml")
    }

    @Test
    fun `merges library uses-permission`() {
        val app = appManifest()
        val lib = writeManifest(
            """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                package="com.example.lib">
                <uses-permission android:name="android.permission.CAMERA" />
            </manifest>
            """.trimIndent()
        )

        val merged = merger().merge(app, listOf(lib), "com.test.app")
        val xml = readMerged(merged)

        assertTrue(xml.contains("android.permission.CAMERA"), "library permission not merged:\n$xml")
    }

    @Test
    fun `does not duplicate existing permissions`() {
        val app = writeManifest(
            """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                package="com.test.app">
                <uses-permission android:name="android.permission.CAMERA" />
                <application android:label="App" />
            </manifest>
            """.trimIndent()
        )
        val lib = writeManifest(
            """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                package="com.example.lib">
                <uses-permission android:name="android.permission.CAMERA" />
            </manifest>
            """.trimIndent()
        )

        val merged = merger().merge(app, listOf(lib), "com.test.app")
        val xml = readMerged(merged)

        val count = Regex("android.permission.CAMERA").findAll(xml).count()
        assertEquals(1, count, "permission should not be duplicated:\n$xml")
    }

    @Test
    fun `handles empty library manifest list`() {
        val app = appManifest()
        val merged = merger().merge(app, emptyList(), "com.test.app")
        val xml = readMerged(merged)

        assertTrue(xml.contains(".MainActivity"), "app manifest should be preserved:\n$xml")
    }

    @Test
    fun `handles library manifest without application element`() {
        val app = appManifest()
        val lib = writeManifest(
            """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                package="com.example.lib">
                <uses-permission android:name="android.permission.CAMERA" />
            </manifest>
            """.trimIndent()
        )

        val merged = merger().merge(app, listOf(lib), "com.test.app")
        val xml = readMerged(merged)

        assertTrue(xml.contains(".MainActivity"), "app manifest preserved:\n$xml")
        assertTrue(xml.contains("CAMERA"), "permission still merged:\n$xml")
    }

    @Test
    fun `preserves app components when library declares same type different name`() {
        val app = appManifest()
        val lib = writeManifest(
            """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                package="com.example.lib">
                <application>
                    <service android:name=".MyService" android:exported="false" />
                </application>
            </manifest>
            """.trimIndent()
        )

        val merged = merger().merge(app, listOf(lib), "com.test.app")
        val xml = readMerged(merged)

        assertTrue(xml.contains(".MainActivity"), "app activity preserved:\n$xml")
        assertTrue(xml.contains(".MyService"), "library service merged:\n$xml")
    }
}
