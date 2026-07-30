package com.hbe.api.dto

data class SigningConfig(
    val type: SigningType,
    val keystorePath: String? = null,
    val keystorePassword: String? = null,
    val keyAlias: String? = null,
    val keyPassword: String? = null,
    val autoGenerate: Boolean = false
) {
    enum class SigningType {
        DEBUG,
        RELEASE,
        NONE
    }

    companion object {
        fun debug() = SigningConfig(
            type = SigningType.DEBUG,
            autoGenerate = true
        )

        fun unsigned() = SigningConfig(
            type = SigningType.NONE
        )
    }
}
