package com.hbe.api

import com.hbe.api.dto.MavenCoordinate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class MavenCoordinateTest {

    @Test
    fun `parses standard three-part coordinate`() {
        val coord = MavenCoordinate.parse("androidx.appcompat:appcompat:1.6.1")
        assertEquals("androidx.appcompat", coord.groupId)
        assertEquals("appcompat", coord.artifactId)
        assertEquals("1.6.1", coord.version)
    }

    @Test
    fun `parses coordinate with classifier`() {
        val coord = MavenCoordinate.parse("org.jetbrains.kotlin:kotlin-stdlib:1.9.0:sources")
        assertEquals("sources", coord.classifier)
    }

    @Test
    fun `throws on invalid coordinate`() {
        assertThrows<IllegalArgumentException> {
            MavenCoordinate.parse("invalid")
        }
    }

    @Test
    fun `toNotation produces correct string`() {
        val coord = MavenCoordinate("com.example", "lib", "2.0")
        assertEquals("com.example:lib:2.0", coord.toNotation())
    }

    @Test
    fun `toPath produces correct path`() {
        val coord = MavenCoordinate("com.example", "lib", "2.0")
        assertEquals("com.example/lib/2.0/lib-2.0.jar", coord.toPath())
    }

    @Test
    fun `equals and hashCode work correctly`() {
        val a = MavenCoordinate.parse("group:artifact:1.0")
        val b = MavenCoordinate("group", "artifact", "1.0")
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }
}
