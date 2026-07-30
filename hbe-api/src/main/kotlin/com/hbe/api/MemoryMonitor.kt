package com.hbe.api

interface MemoryMonitor {
    fun getAvailableMemoryBytes(): Long
    fun getTotalMemoryBytes(): Long
    fun getUsedMemoryBytes(): Long
    fun isLowMemory(): Boolean
    fun getPressure(): MemoryPressure
    fun releaseMemory()
    fun registerPhase(phase: Phase)

    enum class MemoryPressure {
        NONE,
        MODERATE,
        CRITICAL
    }
}
