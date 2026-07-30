package com.hbe.core

import com.hbe.api.EngineConfig
import com.hbe.api.Logger
import com.hbe.api.ProxyConfig
import java.nio.file.Path

class ConfigLoader(
    private val logger: Logger
) {
    fun loadConfig(): EngineConfig {
        val hbeHome = resolveHbeHome()

        return EngineConfig(
            hbeHome = hbeHome,
            sdkHome = resolveSdkHome(hbeHome),
            cacheHome = hbeHome?.resolve("cache"),
            buildOutputDir = hbeHome?.resolve("build"),
            logLevel = System.getenv("HBE_LOG_LEVEL") ?: "INFO"
        )
    }

    fun loadFromFile(path: Path): EngineConfig {
        // TODO: Parse JSON config file
        logger.info("Loading config from file", mapOf("path" to path.toString()))
        return loadConfig()
    }

    fun validate(config: EngineConfig): List<String> {
        val issues = mutableListOf<String>()

        if (config.defaultRamBudgetMb < 256) {
            issues.add("RAM budget ($config.defaultRamBudgetMb MB) is too low; minimum 256 MB")
        }

        return issues
    }

    private fun resolveHbeHome(): Path? {
        val envHome = System.getenv("HBE_HOME")
        if (envHome != null) {
            return Path.of(envHome)
        }
        val userHome = System.getProperty("user.home")
        return if (userHome != null) Path.of(userHome, ".hbe") else null
    }

    private fun resolveSdkHome(hbeHome: Path?): Path? {
        val envSdk = System.getenv("ANDROID_HOME")
            ?: System.getenv("ANDROID_SDK_ROOT")
        if (envSdk != null) {
            return Path.of(envSdk)
        }
        return hbeHome?.resolve("sdk")
    }
}
