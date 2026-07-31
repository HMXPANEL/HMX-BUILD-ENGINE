package com.hbe.signer

import com.hbe.api.ToolResult
import com.hbe.api.ToolRunner
import com.hbe.api.dto.SigningConfig
import io.mockk.every
import io.mockk.mockk
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class SignerImplTest {

    @TempDir
    lateinit var tempDir: Path

    private val logger = com.hbe.core.DefaultLogger()

    private fun signer(toolRunner: ToolRunner): SignerImpl {
        return SignerImpl(com.hbe.infra.OsFileSystem(), toolRunner, logger)
    }

    @Test
    fun `verify parses v1 v2 v3 and certificate`() {
        val toolRunner = mockk<ToolRunner>()
        every { toolRunner.run("apksigner", any(), any()) } returns ToolResult(
            0,
            """
                Verifies
                Verified using v1 scheme (JAR signing): true
                Verified using v2 scheme (APK Signature Scheme v2): true
                Verified using v3 scheme (APK Signature Scheme v3): false
                Number of signers: 1
                Signer #1 certificate DN: CN=Android Debug, O=Android, C=US
                Signer #1 certificate SHA-256 digest: abc
                Signer #1 public key: RSA
            """.trimIndent(),
            "",
            30,
            true
        )
        val apk = tempDir.resolve("app.apk")
        Files.write(apk, byteArrayOf(1))

        val info = signer(toolRunner).verify(apk)

        assertTrue(info.isSigned)
        assertTrue(info.v1Signed)
        assertTrue(info.v2Signed)
        assertFalse(info.v3Signed)
        assertEquals("CN=Android Debug, O=Android, C=US", info.signerSubject)
    }

    @Test
    fun `verify returns unsigned when apksigner fails`() {
        val toolRunner = mockk<ToolRunner>()
        every { toolRunner.run("apksigner", any(), any()) } returns ToolResult(
            1, "", "DOES NOT VERIFY", 15, false
        )
        val apk = tempDir.resolve("app.apk")
        Files.write(apk, byteArrayOf(1))

        val info = signer(toolRunner).verify(apk)

        assertFalse(info.isSigned)
    }

    @Test
    fun `sign with NONE type does not invoke apksigner`() {
        val toolRunner = mockk<ToolRunner>()
        val apk = tempDir.resolve("app.apk")
        Files.write(apk, byteArrayOf(1))

        val signed = signer(toolRunner).sign(apk, SigningConfig.unsigned())

        assertFalse(signed.v1Signed)
        assertEquals(apk, signed.apkPath)
    }

    @Test
    fun `release sign without keystore throws`() {
        val toolRunner = mockk<ToolRunner>()
        val apk = tempDir.resolve("app.apk")
        Files.write(apk, byteArrayOf(1))

        assertThrows(com.hbe.api.exception.SigningException::class.java) {
            signer(toolRunner).sign(apk, SigningConfig(SigningConfig.SigningType.RELEASE))
        }
    }
}
