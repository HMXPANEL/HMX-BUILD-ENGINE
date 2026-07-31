package com.hbe.api.exception

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BuildExceptionTest {

    @Test
    fun `BuildException has correct error code`() {
        val ex = BuildException(message = "Test message", errorCode = "TEST_ERROR")
        assertEquals("TEST_ERROR", ex.errorCode)
        assertEquals("Test message", ex.message)
    }

    @Test
    fun `SDK exception includes suggestion`() {
        val ex = SdkException(sdkVersion = 34)
        assertTrue(ex.message.contains("android-34"))
        assertTrue(ex.suggestion?.isNotEmpty() == true)
    }

    @Test
    fun `Compiler exception includes phase and errors`() {
        val errors = listOf(
            CompilerError("File.kt", 10, 5, "Syntax error")
        )
        val ex = CompilerException("Compilation failed", "kotlinc", errors)
        assertEquals("kotlinc", ex.phase)
        assertEquals(1, ex.errors.size)
    }

    @Test
    fun `Network exception includes URL`() {
        val ex = NetworkException("Connection failed", url = "https://example.com")
        assertEquals("https://example.com", ex.url)
        assertTrue(ex.isRecoverable)
    }

    @Test
    fun `Configuration exception is not recoverable`() {
        val ex = ConfigurationException("Invalid config")
        assertTrue(!ex.isRecoverable)
    }

    @Test
    fun `Cache exception is recoverable`() {
        val ex = CacheException("Cache corrupted")
        assertTrue(ex.isRecoverable)
    }
}
