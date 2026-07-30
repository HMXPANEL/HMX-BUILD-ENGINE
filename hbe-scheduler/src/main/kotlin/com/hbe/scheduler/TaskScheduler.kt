package com.hbe.scheduler

import com.hbe.api.*
import com.hbe.api.dto.BuildResult
import com.hbe.api.dto.PhaseTiming
import com.hbe.api.dto.BuildError
import com.hbe.graph.BuildGraph
import com.hbe.graph.BuildNode

class TaskScheduler(
    private val cacheManager: CacheManager,
    private val memoryMonitor: MemoryMonitor,
    private val logger: Logger
) {
    fun schedule(graph: BuildGraph, context: BuildContext): ExecutionPlan {
        val sortedNodes = graph.topologicalSort()
        val batches = createBatches(sortedNodes, context.config.defaultRamBudgetMb)

        return ExecutionPlan(batches = batches)
    }

    fun execute(context: BuildContext, plan: ExecutionPlan): List<PhaseTiming> {
        val timings = mutableListOf<PhaseTiming>()

        for (batch in plan.batches) {
            if (context.cancellationToken.isCancelled) break

            for (node in batch.nodes) {
                context.cancellationToken.throwIfCancelled()

                logger.info("Executing phase: ${node.label}", mapOf("node" to node.id))
                memoryMonitor.releaseMemory()
            }
        }

        return timings
    }

    private fun createBatches(nodes: List<BuildNode>, ramBudget: Int): List<ExecutionBatch> {
        val batches = mutableListOf<ExecutionBatch>()
        val currentBatch = mutableListOf<BuildNode>()
        var currentMemory = 0L

        for (node in nodes) {
            if (currentMemory + node.estimatedMemoryMb > ramBudget && currentBatch.isNotEmpty()) {
                batches.add(ExecutionBatch(currentBatch.toList(), currentMemory))
                currentBatch.clear()
                currentMemory = 0L
            }
            currentBatch.add(node)
            currentMemory += node.estimatedMemoryMb
        }

        if (currentBatch.isNotEmpty()) {
            batches.add(ExecutionBatch(currentBatch.toList(), currentMemory))
        }

        return batches
    }
}

data class ExecutionPlan(
    val batches: List<ExecutionBatch>
)

data class ExecutionBatch(
    val nodes: List<BuildNode>,
    val estimatedMemoryMb: Long = 0,
    val isParallel: Boolean = false
)
