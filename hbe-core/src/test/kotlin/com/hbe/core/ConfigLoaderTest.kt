package com.hbe.core

import com.hbe.api.EngineConfig
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ConfigLoaderTest {

    @Test
    fun `validate passes for default config`() {
        val config = EngineConfig(defaultRamBudgetMb = 1024)
        val issues = ConfigLoader(DefaultLogger()).validate(config)
        assertTrue(issues.isEmpty())
    }

    @Test
    fun `validate warns on low RAM budget`() {
        val config = EngineConfig(defaultRamBudgetMb = 128)
        val issues = ConfigLoader(DefaultLogger()).validate(config)
        assertTrue(issues.any { it.contains("RAM budget") })
    }
}
