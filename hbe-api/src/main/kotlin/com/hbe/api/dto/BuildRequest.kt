package com.hbe.api.dto

data class BuildRequest(
    val projectDir: String,
    val variant: String = "debug",
    val clean: Boolean = false,
    val incremental: Boolean = true,
    val minSdk: Int? = null,
    val targetSdk: Int? = null,
    val compileSdk: Int? = null,
    val buildToolsVersion: String? = null,
    val signingConfig: SigningConfig? = null,
    val mavenRepos: List<String> = emptyList(),
    val dependencies: List<String> = emptyList(),
    val proguardRules: String? = null,
    val ramBudgetMb: Int = 1024,
    val compose: Boolean = false,
    val kotlinVersion: String? = null,
    val outputApkPath: String? = null,
    val daemon: Boolean = false,
    val properties: Map<String, String> = emptyMap()
)
