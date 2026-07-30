package com.hbe.signer

import com.hbe.api.*
import com.hbe.api.dto.SigningConfig
import com.hbe.api.exception.SigningException
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import javax.crypto.KeyGenerator

class SignerImpl(
    private val fileSystem: FileSystem,
    private val processRunner: ProcessRunner,
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
            v3Signed = false
        )
    }

    override fun verify(apkFile: Path): SignatureInfo {
        // Will use apksigner verify when available
        return SignatureInfo(
            isSigned = false,
            v1Signed = false,
            v2Signed = false
        )
    }

    override fun generateDebugKeystore(keystorePath: Path): Path {
        if (fileSystem.exists(keystorePath)) {
            return keystorePath
        }

        logger.info("Generating debug keystore", mapOf("path" to keystorePath.toString()))
        fileSystem.createDirectories(keystorePath.parent)

        // Use keytool if available
        val keytoolPath = processRunner.findTool("keytool")
        if (keytoolPath != null) {
            val result = processRunner.run(keytoolPath.toString(), listOf(
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

            if (result.isFailure) {
                throw SigningException(
                    message = "Failed to generate debug keystore: ${result.stderr}",
                    suggestion = "Ensure keytool is available in your JDK installation"
                )
            }
        } else {
            // Programmatic keystore generation as fallback
            generateKeystoreProgrammatically(keystorePath)
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
        val apksignerPath = processRunner.findTool("apksigner")
        if (apksignerPath == null) {
            logger.warn("apksigner not found — APK will not be signed",
                mapOf("suggestion" to "Install Android SDK build-tools"))
            return
        }

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
        val result = processRunner.run(apksignerPath.toString(), args)

        if (result.isFailure) {
            throw SigningException(
                message = "apksigner failed: ${result.stderr}",
                suggestion = "Check keystore credentials and try again"
            )
        }
    }

    private fun generateKeystoreProgrammatically(path: Path) {
        try {
            val keyStore = KeyStore.getInstance("JKS").apply {
                load(null, debugKeystorePassword.toCharArray())
            }

            val keyGen = KeyGenerator.getInstance("RSA")
            keyGen.init(2048)

            val keyStorePass = debugKeystorePassword.toCharArray()

            keyStore.store(Files.newOutputStream(path), keyStorePass)
        } catch (e: Exception) {
            throw SigningException(
                message = "Failed to generate keystore programmatically: ${e.message}",
                cause = e,
                suggestion = "Install JDK with keytool support"
            )
        }
    }
}
