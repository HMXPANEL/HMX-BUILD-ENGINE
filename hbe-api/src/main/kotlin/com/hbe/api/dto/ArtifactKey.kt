package com.hbe.api.dto

data class ArtifactKey(
    val phase: String,
    val projectId: String,
    val inputHash: String,
    val toolVersion: String,
    val variant: String
) {
    fun toCachePath(): String {
        val hash = hashCode().toLong().let {
            if (it < 0) -it else it
        }.toString(16)
        return "${hash.substring(0, 2)}/$hash"
    }
}
