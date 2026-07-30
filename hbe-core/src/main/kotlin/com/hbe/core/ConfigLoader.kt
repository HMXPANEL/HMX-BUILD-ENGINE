package com.hbe.core

import com.hbe.api.EngineConfig
import com.hbe.api.Logger
import com.hbe.api.ProxyConfig
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.nio.file.Path

class ConfigLoader(
    private val logger: Logger
) {
    private val mapper = jacksonObjectMapper()

    fun loadConfig(): EngineConfig {
        val hbeHome = resolveHbeHome()
        val configFile = hbeHome?.resolve("hbe.json")
        if (configFile != null && java.nio.file.Files.exists(configFile)) {
            return loadFromFile(configFile)
        }
        return defaultConfig(hbeHome)
    }

    fun loadFromFile(path: Path): EngineConfig {
        logger.info("Loading config from file", mapOf("path" to path.toString()))
        if (!java.nio.file.Files.exists(path)) {
            logger.warn("Config file not found, using defaults", mapOf("path" to path.toString()))
            return defaultConfig(resolveHbeHome())
        }
        return try {
            val text = java.nio.file.Files.readString(path)
            val overrides = mapper.readValue<Map<String, Any?>>(text)
            val base = defaultConfig(resolveHbeHome())
            applyOverrides(base, overrides)
        } catch (e: Exception) {
            logger.warn("Failed to parse config file, using defaults", mapOf(
                "path" to path.toString(), "error" to (e.message ?: "")
            ))
            defaultConfig(resolveHbeHome())
        }
    }

    fun validate(config: EngineConfig): List<String> {
        val issues = mutableListOf<String>()

        if (config.defaultRamBudgetMb < 256) {
            issues.add("RAM budget ($config.defaultRamBudgetMb MB) is too low; minimum 256 MB")
        }

        return issues
    }

    private fun defaultConfig(hbeHome: Path?): EngineConfig {
        return EngineConfig(
            hbeHome = hbeHome,
            sdkHome = resolveSdkHome(hbeHome),
            cacheHome = hbeHome?.resolve("cache"),
            buildOutputDir = hbeHome?.resolve("build"),
            logLevel = System.getenv("HBE_LOG_LEVEL") ?: "INFO"
        )
    }

    private fun applyOverrides(base: EngineConfig, overrides: Map<String, Any?>): EngineConfig {
        var config = base
        for ((key, value) in overrides) {
            if (value == null) continue
            config = when (key) {
                "sdkHome" -> config.copy(sdkHome = Path.of(value.toString()))
                "cacheMaxBytes" -> config.copy(cacheMaxBytes = (value as Number).toLong())
                "defaultRamBudgetMb" -> config.copy(defaultRamBudgetMb = (value as Number).toInt())
                "logLevel" -> config.copy(logLevel = value.toString())
                "maxRamBudgetMb" -> config.copy(maxRamBudgetMb = (value as Number).toInt())
                "autoTune" -> config.copy(autoTune = value.toString().toBoolean())
                else -> config
            }
        }
        return config
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
