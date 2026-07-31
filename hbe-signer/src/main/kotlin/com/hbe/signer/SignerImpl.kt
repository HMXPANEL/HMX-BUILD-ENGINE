package com.hbe.signer

import com.hbe.api.*
import com.hbe.api.dto.SigningConfig
import com.hbe.api.exception.SigningException
import java.nio.file.Files
import java.nio.file.Path

class SignerImpl(
    private val fileSystem: FileSystem,
    private val toolRunner: ToolRunner,
    private val logger: Logger
) : Signer {

    private val debugKeystorePassword = "android"
    private val debugKeyAlias = "androiddebugkey"
    private val debugKeyPassword = "android"

    override fun sign(apkFile: Path, config: SigningConfig): SignedApk {
        logger.info("Signing APK", mapOf("path" to apkFile.toString(), "type" to config.type.name))

        when (config.type) {
            SigningConfig.SigningType.DEBUG -> signWithDebugKey(apkFile)
            SigningConfig.SigningType.RELEASE -> signWithReleaseKey(apkFile, config)
            SigningConfig.SigningType.NONE -> {
                logger.info("APK unsigned (signing type: NONE)")
            }
        }

        return SignedApk(
            apkPath = apkFile,
            sizeBytes = fileSystem.size(apkFile),
            v1Signed = config.type != SigningConfig.SigningType.NONE,
            v2Signed = config.type != SigningConfig.SigningType.NONE,
            v3Signed = config.type != SigningConfig.SigningType.NONE
        )
    }

    override fun verify(apkFile: Path): SignatureInfo {
        val result = toolRunner.run("apksigner", listOf("verify", "--print-certs", apkFile.toString()))
        if (!result.succeeded) {
            return SignatureInfo(isSigned = false)
        }

        val output = result.stdout + "\n" + result.stderr
        return SignatureInfo(
            isSigned = true,
            v1Signed = output.contains("v1 scheme (JAR signing): true"),
            v2Signed = output.contains("v2 scheme (APK Signature Scheme v2): true"),
            v3Signed = output.contains("v3 scheme (APK Signature Scheme v3): true"),
            signerSubject = output.lines()
                .firstOrNull { it.contains("certificate DN:") }
                ?.substringAfter("certificate DN:")?.trim(),
            signatureAlgorithm = output.lines()
                .firstOrNull { it.contains("signature algorithm:", ignoreCase = true) }
                ?.substringAfter("algorithm:")?.trim()
        )
    }

    override fun generateDebugKeystore(keystorePath: Path): Path {
        if (fileSystem.exists(keystorePath)) {
            return keystorePath
        }

        logger.info("Generating debug keystore", mapOf("path" to keystorePath.toString()))
        fileSystem.createDirectories(keystorePath.parent)

        val result = toolRunner.run("keytool", listOf(
            "-genkey", "-v",
            "-keystore", keystorePath.toString(),
            "-alias", debugKeyAlias,
            "-keyalg", "RSA",
            "-keysize", "2048",
            "-validity", "10000",
            "-dname", "CN=Android Debug, O=Android, C=US",
            "-storepass", debugKeystorePassword,
            "-keypass", debugKeyPassword
        ))

        if (!result.succeeded) {
            throw SigningException(
                message = "Failed to generate debug keystore: ${result.stderr}",
                suggestion = "Ensure keytool is available in your JDK installation"
            )
        }

        return keystorePath
    }

    private fun signWithDebugKey(apkFile: Path) {
        val keystorePath = findOrCreateDebugKeystore()
        signWithApksigner(apkFile, keystorePath, debugKeystorePassword, debugKeyAlias, debugKeyPassword)
    }

    private fun signWithReleaseKey(apkFile: Path, config: SigningConfig) {
        val keystorePath = config.keystorePath?.let { Path.of(it) }
            ?: throw SigningException("Release keystore path not specified")

        if (!fileSystem.exists(keystorePath)) {
            throw SigningException(
                message = "Release keystore not found: $keystorePath",
                suggestion = "Check the keystore path in your signing configuration"
            )
        }

        signWithApksigner(
            apkFile,
            keystorePath,
            config.keystorePassword ?: "",
            config.keyAlias ?: "",
            config.keyPassword ?: ""
        )
    }

    private fun findOrCreateDebugKeystore(): Path {
        val userHome = System.getProperty("user.home")
        val keystorePath = Path.of(userHome, ".hbe", "debug.keystore")
        return if (fileSystem.exists(keystorePath)) keystorePath
        else generateDebugKeystore(keystorePath)
    }

    private fun signWithApksigner(
        apkFile: Path,
        keystore: Path,
        keystorePass: String,
        keyAlias: String,
        keyPass: String
    ) {
        val args = mutableListOf(
            "sign",
            "--ks", keystore.toString(),
            "--ks-pass", "pass:$keystorePass",
            "--ks-key-alias", keyAlias,
            "--key-pass", "pass:$keyPass",
            "--v1-signing-enabled", "true",
            "--v2-signing-enabled", "true",
            "--v3-signing-enabled", "true",
            apkFile.toString()
        )
        val result = toolRunner.run("apksigner", args)

        if (!result.succeeded) {
            throw SigningException(
                message = "apksigner failed: ${result.stderr}",
                suggestion = "Check keystore credentials and try again"
            )
        }
    }
}
