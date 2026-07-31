package com.hbe.scheduler

import com.hbe.api.*
import com.hbe.api.dto.PhaseTiming
import com.hbe.graph.BuildEdge
import com.hbe.graph.BuildGraph
import com.hbe.graph.BuildNode
import com.hbe.graph.NodeType
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TaskSchedulerTest {

    private val logger = NoopLogger()
    private val memoryMonitor = mockMemoryMonitor()

    private fun context(
        config: EngineConfig = EngineConfig(),
        token: CancellationToken = TestToken()
    ): BuildContext {
        return TestBuildContext("bld", com.hbe.api.dto.BuildRequest("."), config, token)
    }

    @Test
    fun `schedule produces one batch per topological level`() {
        val graph = BuildGraph(
            nodes = listOf(
                BuildNode("a", NodeType.SDK_RESOLVE),
                BuildNode("b", NodeType.RES_COMPILE),
                BuildNode("c", NodeType.SOURCE_COMPILE),
                BuildNode("d", NodeType.DEX)
            ),
            edges = listOf(
                BuildEdge("a", "b"),
                BuildEdge("a", "c"),
                BuildEdge("b", "d"),
                BuildEdge("c", "d")
            )
        )
        val context = context()

        val plan = TaskScheduler(memoryMonitor, logger).schedule(graph, context)

        assertEquals(3, plan.batches.size)
        assertEquals(listOf("a"), plan.batches[0].nodes.map { it.id })
        assertEquals(setOf("b", "c"), plan.batches[1].nodes.map { it.id }.toSet())
        assertEquals(listOf("d"), plan.batches[2].nodes.map { it.id })
        assertTrue(plan.batches[1].isParallel)
        assertFalse(plan.batches[0].isParallel)
    }

    @Test
    fun `execute runs dependent batches in order`() {
        val graph = BuildGraph(
            nodes = listOf(
                BuildNode("a", NodeType.SDK_RESOLVE),
                BuildNode("b", NodeType.DEX)
            ),
            edges = listOf(BuildEdge("a", "b"))
        )
        val context = context()
        val executed = mutableListOf<String>()

        val plan = TaskScheduler(memoryMonitor, logger).schedule(graph, context)
        val timings = TaskScheduler(memoryMonitor, logger).execute(context, plan) { node ->
            executed.add(node.id)
            PhaseTiming(node.id, PhaseTiming.PhaseStatus.SUCCESS)
        }

        assertEquals(listOf("a", "b"), executed)
        assertEquals(listOf("a", "b"), timings.map { it.name })
    }

    @Test
    fun `parallel batch executes independent nodes concurrently`() {
        val graph = BuildGraph(
            nodes = listOf(
                BuildNode("left", NodeType.RES_COMPILE),
                BuildNode("right", NodeType.SOURCE_COMPILE)
            )
        )
        val context = context(config = EngineConfig(parallelPhases = true))
        val active = AtomicInteger()
        val maxActive = AtomicInteger()
        val started = AtomicLong()

        val plan = TaskScheduler(memoryMonitor, logger).schedule(graph, context)
        TaskScheduler(memoryMonitor, logger).execute(context, plan) { node ->
            val now = active.incrementAndGet()
            started.incrementAndGet()
            Thread.sleep(200)
            maxActive.updateAndGet { maxOf(it, now) }
            active.decrementAndGet()
            PhaseTiming(node.id, PhaseTiming.PhaseStatus.SUCCESS, durationMs = 200)
        }

        assertEquals(2, started.get())
        assertEquals(2, maxActive.get(), "independent nodes should have run in parallel")
    }

    @Test
    fun `sequential execution never exceeds one concurrent node`() {
        val graph = BuildGraph(
            nodes = listOf(
                BuildNode("left", NodeType.RES_COMPILE),
                BuildNode("right", NodeType.SOURCE_COMPILE)
            )
        )
        val context = context(config = EngineConfig(parallelPhases = false))
        val active = AtomicInteger()
        val maxActive = AtomicInteger()

        val plan = TaskScheduler(memoryMonitor, logger).schedule(graph, context)
        TaskScheduler(memoryMonitor, logger).execute(context, plan) { node ->
            val now = active.incrementAndGet()
            Thread.sleep(50)
            maxActive.updateAndGet { maxOf(it, now) }
            active.decrementAndGet()
            PhaseTiming(node.id, PhaseTiming.PhaseStatus.SUCCESS)
        }

        assertEquals(1, maxActive.get())
    }

    @Test
    fun `cancelled context stops execution`() {
        val graph = BuildGraph(
            nodes = listOf(
                BuildNode("a", NodeType.RES_COMPILE),
                BuildNode("b", NodeType.DEX)
            ),
            edges = listOf(BuildEdge("a", "b"))
        )
        val token = TestToken().apply { cancel() }
        val context = context(token = token)
        var calls = 0

        val plan = TaskScheduler(memoryMonitor, logger).schedule(graph, context)
        val timings = TaskScheduler(memoryMonitor, logger).execute(context, plan) { node ->
            calls++
            PhaseTiming(node.id, PhaseTiming.PhaseStatus.SUCCESS)
        }

        assertEquals(0, calls)
        assertTrue(timings.isEmpty())
    }

    private fun mockMemoryMonitor(): MemoryMonitor {
        return object : MemoryMonitor {
            override fun getAvailableMemoryBytes(): Long = 1024L * 1024 * 1024
            override fun getTotalMemoryBytes(): Long = 2048L * 1024 * 1024
            override fun getUsedMemoryBytes(): Long = 1024L * 1024 * 1024
            override fun isLowMemory(): Boolean = false
            override fun getPressure(): MemoryMonitor.MemoryPressure = MemoryMonitor.MemoryPressure.NONE
            override fun releaseMemory() {}
            override fun registerPhase(phase: Phase) {}
        }
    }

    private class TestBuildContext(
        override val buildId: String,
        override val request: com.hbe.api.dto.BuildRequest,
        override val config: EngineConfig,
        override val cancellationToken: CancellationToken
    ) : BuildContext {
        override val startTime: Instant = Instant.now()
        private val meta = mutableMapOf<String, Any>()
        private val state = mutableMapOf<String, Any>()
        override fun metadata(key: String): Any? = meta[key]
        override fun setMetadata(key: String, value: Any) { meta[key] = value }
        override fun <T> getState(key: String, type: Class<T>): T? {
            val value = state[key] ?: return null
            return if (type.isInstance(value)) type.cast(value) else null
        }
        override fun setState(key: String, value: Any) { state[key] = value }
    }

    private class TestToken : CancellationToken {
        @Volatile
        override var isCancelled = false
        override fun cancel() { isCancelled = true }
        override fun throwIfCancelled() {
            if (isCancelled) throw IllegalStateException("cancelled")
        }
    }

    private class NoopLogger : Logger {
        override fun debug(message: String, context: Map<String, Any>) {}
        override fun info(message: String, context: Map<String, Any>) {}
        override fun warn(message: String, context: Map<String, Any>) {}
        override fun error(message: String, context: Map<String, Any>) {}
        override fun trace(message: String, context: Map<String, Any>) {}
        override fun setLevel(level: Logger.LogLevel) {}
        override fun setOutput(output: Logger.LogOutput) {}
    }
}
