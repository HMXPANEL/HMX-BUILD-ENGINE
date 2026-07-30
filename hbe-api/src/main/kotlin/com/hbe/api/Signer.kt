package com.hbe.api

import com.hbe.api.dto.SigningConfig
import java.nio.file.Path

interface Signer {
    fun sign(apkFile: Path, config: SigningConfig): SignedApk
    fun verify(apkFile: Path): SignatureInfo
    fun generateDebugKeystore(keystorePath: Path): Path
}

data class SignedApk(
    val apkPath: Path,
    val sizeBytes: Long = 0,
    val aligned: Boolean = false,
    val v1Signed: Boolean = false,
    val v2Signed: Boolean = false,
    val v3Signed: Boolean = false
)

data class SignatureInfo(
    val isSigned: Boolean,
    val v1Signed: Boolean = false,
    val v2Signed: Boolean = false,
    val v3Signed: Boolean = false,
    val signerSubject: String? = null,
    val signerIssuer: String? = null,
    val signatureAlgorithm: String? = null
)
