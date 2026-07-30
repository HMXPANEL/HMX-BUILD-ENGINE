package com.hbe.api.dto

data class BuildError(
    val phase: String,
    val code: String,
    val message: String,
    val suggestion: String? = null,
    val details: List<String> = emptyList()
)
