package com.hbe.scheduler

import com.hbe.api.*
import com.hbe.api.dto.PhaseTiming
import com.hbe.graph.BuildGraph
import com.hbe.graph.BuildNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking

class TaskScheduler(
    private val memoryMonitor: MemoryMonitor,
    private val logger: Logger
) {
    /** Builds an execution plan from the task graph: one batch per topological level,
     *  where nodes within a level are dependency-independent and may run in parallel. */
    fun schedule(graph: BuildGraph, context: BuildContext): ExecutionPlan {
        val levels = graph.levels()
        val batches = levels.map { level ->
            ExecutionBatch(
                nodes = level,
                estimatedMemoryMb = level.sumOf { it.estimatedMemoryMb },
                isParallel = level.size > 1
            )
        }
        return ExecutionPlan(batches = batches)
    }

    /** Runs the plan. Batches execute strictly in order; nodes within a batch run in
     *  parallel (when the batch is parallel-safe and EngineConfig.parallelPhases is
     *  enabled) or sequentially. */
    fun execute(
        context: BuildContext,
        plan: ExecutionPlan,
        executor: (BuildNode) -> PhaseTiming
    ): List<PhaseTiming> {
        val timings = mutableListOf<PhaseTiming>()

        for (batch in plan.batches) {
            if (context.cancellationToken.isCancelled) break

            val parallel = batch.isParallel && context.config.parallelPhases
            logger.info("Executing batch", mapOf(
                "nodes" to batch.nodes.joinToString { it.id },
                "parallel" to parallel.toString()
            ))

            if (parallel) {
                val batchTimings = runBlocking {
                    batch.nodes
                        .map { node -> async(Dispatchers.IO) { executor(node) } }
                        .awaitAll()
                }
                timings.addAll(batchTimings)
            } else {
                for (node in batch.nodes) {
                    context.cancellationToken.throwIfCancelled()
                    timings.add(executor(node))
                }
            }

            memoryMonitor.releaseMemory()
        }

        return timings
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
