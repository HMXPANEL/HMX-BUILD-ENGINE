package com.hbe.memory

import com.hbe.api.MemoryMonitor
import com.hbe.api.MemoryMonitor.MemoryPressure
import com.hbe.api.Phase
import com.hbe.api.Logger
import java.lang.management.ManagementFactory

class MemoryManagerImpl(
    private val logger: Logger,
    private val totalMemoryBytes: Long = probeSystemTotalMemory()
) : MemoryMonitor {

    private var phaseRegistry = mutableListOf<Phase>()

    override fun getAvailableMemoryBytes(): Long {
        val osBean = ManagementFactory.getOperatingSystemMXBean()
        if (osBean is com.sun.management.OperatingSystemMXBean) {
            return osBean.freeMemorySize
        }
        val runtime = Runtime.getRuntime()
        val maxMemory = runtime.maxMemory()
        val totalMemory = runtime.totalMemory()
        val freeMemory = runtime.freeMemory()
        return (maxMemory - totalMemory) + freeMemory
    }

    override fun getTotalMemoryBytes(): Long = totalMemoryBytes

    override fun getUsedMemoryBytes(): Long = totalMemoryBytes - getAvailableMemoryBytes()

    override fun isLowMemory(): Boolean {
        return getAvailableMemoryBytes() < 512L * 1024 * 1024
    }

    companion object {
        private fun probeSystemTotalMemory(): Long {
            val osBean = ManagementFactory.getOperatingSystemMXBean()
            if (osBean is com.sun.management.OperatingSystemMXBean) {
                return osBean.totalMemorySize
            }
            return Runtime.getRuntime().maxMemory()
        }
    }

    override fun getPressure(): MemoryPressure {
        val available = getAvailableMemoryBytes()
        val total = getTotalMemoryBytes()
        val ratio = if (total > 0) available.toDouble() / total else 0.0

        return when {
            ratio > 0.4 -> MemoryPressure.NONE
            ratio > 0.2 -> MemoryPressure.MODERATE
            else -> MemoryPressure.CRITICAL
        }
    }

    override fun releaseMemory() {
        System.gc()
        System.runFinalization()
        try {
            Thread.sleep(50)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }

        logger.debug("Memory released", mapOf(
            "availableMb" to (getAvailableMemoryBytes() / (1024 * 1024)).toString()
        ))
    }

    override fun registerPhase(phase: Phase) {
        phaseRegistry.add(phase)
    }

    fun computeBatchSize(phase: Phase, allocatedBytes: Long): Int {
        return when {
            phase.name.contains("Java", ignoreCase = true) -> {
                val perProcessOverhead = 50L * 1024 * 1024
                val perFileCost = 5L * 1024 * 1024
                kotlin.math.max(1, ((allocatedBytes - perProcessOverhead) / perFileCost).toInt())
            }
            phase.name.contains("Kotlin", ignoreCase = true) -> {
                val perProcessOverhead = 80L * 1024 * 1024
                val perFileCost = 12L * 1024 * 1024
                kotlin.math.max(1, ((allocatedBytes - perProcessOverhead) / perFileCost).toInt())
            }
            phase.name.contains("Dex", ignoreCase = true) -> {
                val perProcessOverhead = 60L * 1024 * 1024
                val perClassFileCost = 256L * 1024
                kotlin.math.max(1, ((allocatedBytes - perProcessOverhead) / perClassFileCost).toInt())
            }
            else -> 50
        }
    }
}
