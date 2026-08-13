package com.hbe.core.project

import com.hbe.core.DefaultLogger
import com.hbe.infra.OsFileSystem
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.io.TempDir

class ProjectImporterTest {

    @TempDir
    lateinit var tempDir: Path

    private fun write(path: Path, content: String) = Files.writeString(path, content)
    private fun dir(path: Path) = Files.createDirectories(path)

    @Test
    fun `detects multiple modules from settings gradle`() {
        write(tempDir.resolve("settings.gradle"), """
            rootProject.name = "MultiMod"
            include(":app", ":lib", ":lib:sub")
        """.trimIndent())
        dir(tempDir.resolve("app"))
        dir(tempDir.resolve("lib"))
        dir(tempDir.resolve("lib/sub"))
        write(tempDir.resolve("app/build.gradle"), "android { namespace 'com.x.app' }")
        write(tempDir.resolve("lib/build.gradle"), "android { namespace 'com.x.lib' }")
        write(tempDir.resolve("lib/sub/build.gradle"), "android { namespace 'com.x.sub' }")

        val model = ProjectImporter(OsFileSystem(), DefaultLogger()).importProject(tempDir)

        assertEquals(3, model.modules.size)
        val paths = model.modules.map { it.path }.toSet()
        assertEquals(setOf(":app", ":lib", ":lib:sub"), paths)
    }

    @Test
    fun `extracts project to project dependencies`() {
        write(tempDir.resolve("settings.gradle"), """
            include(":app", ":core", ":ui")
        """.trimIndent())
        dir(tempDir.resolve("app"))
        dir(tempDir.resolve("core"))
        dir(tempDir.resolve("ui"))
        write(tempDir.resolve("app/build.gradle"), """
            dependencies {
                implementation project(":core")
                api project(path = ":ui")
                implementation 'androidx.core:core-ktx:1.12.0'
            }
        """.trimIndent())
        write(tempDir.resolve("core/build.gradle"), "android { namespace 'com.x.core' }")
        write(tempDir.resolve("ui/build.gradle"), "android { namespace 'com.x.ui' }")

        val model = ProjectImporter(OsFileSystem(), DefaultLogger()).importProject(tempDir)
        val app = model.modules.first { it.path == ":app" }

        assertEquals(listOf(":core", ":ui"), app.projectDependencies)
        assertTrue(app.dependencies.contains("androidx.core:core-ktx:1.12.0"))
    }

    @Test
    fun `moduleOrder puts dependencies before dependents`() {
        write(tempDir.resolve("settings.gradle"), "include(':app', ':core', ':ui')")
        dir(tempDir.resolve("app"))
        dir(tempDir.resolve("core"))
        dir(tempDir.resolve("ui"))
        write(tempDir.resolve("app/build.gradle"), "dependencies { implementation project(':core'); implementation project(':ui') }")
        write(tempDir.resolve("core/build.gradle"), "dependencies { implementation project(':ui') }")
        write(tempDir.resolve("ui/build.gradle"), "android { namespace 'com.x.ui' }")

        val model = ProjectImporter(OsFileSystem(), DefaultLogger()).importProject(tempDir)
        val order = model.moduleOrder.map { it.path }

        assertTrue(order.indexOf(":ui") < order.indexOf(":core"), "ui before core")
        assertTrue(order.indexOf(":core") < order.indexOf(":app"), "core before app")
    }
}
