package com.hbe.core.project

import com.hbe.infra.OsFileSystem
import com.hbe.core.DefaultLogger
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class ProjectImporterTest {

    private fun write(path: Path, content: String) =
        Files.writeString(path, content)

    @Test
    fun `detects multiple modules from settings gradle`(@TempDir root: Path) {
        write(root.resolve("settings.gradle"), """
            rootProject.name = "MultiMod"
            include(":app", ":lib", ":lib:sub")
        """.trimIndent())
        write(root.resolve("app/build.gradle"), "android { namespace 'com.x.app' }")
        write(root.resolve("lib/build.gradle"), "android { namespace 'com.x.lib' }")
        write(root.resolve("lib/sub/build.gradle"), "android { namespace 'com.x.sub' }")

        val model = ProjectImporter(OsFileSystem(), DefaultLogger()).importProject(root)

        assertEquals(3, model.modules.size)
        val paths = model.modules.map { it.path }.toSet()
        assertEquals(setOf(":app", ":lib", ":lib:sub"), paths)
    }

    @Test
    fun `extracts project to project dependencies`(@TempDir root: Path) {
        write(root.resolve("settings.gradle"), """
            include(":app", ":core", ":ui")
        """.trimIndent())
        write(root.resolve("app/build.gradle"), """
            dependencies {
                implementation project(":core")
                api project(path = ":ui")
                implementation 'androidx.core:core-ktx:1.12.0'
            }
        """.trimIndent())
        write(root.resolve("core/build.gradle"), "android { namespace 'com.x.core' }")
        write(root.resolve("ui/build.gradle"), "android { namespace 'com.x.ui' }")

        val model = ProjectImporter(OsFileSystem(), DefaultLogger()).importProject(root)
        val app = model.modules.first { it.path == ":app" }

        assertEquals(listOf(":core", ":ui"), app.projectDependencies)
        assertTrue(app.dependencies.contains("androidx.core:core-ktx:1.12.0"))
    }

    @Test
    fun `moduleOrder puts dependencies before dependents`(@TempDir root: Path) {
        write(root.resolve("settings.gradle"), "include(':app', ':core', ':ui')")
        write(root.resolve("app/build.gradle"), "dependencies { implementation project(':core'); implementation project(':ui') }")
        write(root.resolve("core/build.gradle"), "dependencies { implementation project(':ui') }")
        write(root.resolve("ui/build.gradle"), "android { namespace 'com.x.ui' }")

        val model = ProjectImporter(OsFileSystem(), DefaultLogger()).importProject(root)
        val order = model.moduleOrder.map { it.path }

        assertTrue(order.indexOf(":ui") < order.indexOf(":core"), "ui before core")
        assertTrue(order.indexOf(":core") < order.indexOf(":app"), "core before app")
    }
}
