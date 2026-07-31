package com.hbe.core.event

import com.hbe.api.ToolOptions
import com.hbe.api.ToolResult
import com.hbe.api.ToolRunner
import com.hbe.api.event.BuildEventBus
import com.hbe.api.event.ToolExecutionFinishedEvent
import com.hbe.api.event.ToolExecutionStartedEvent

class EventEmittingToolRunner(
    private val delegate: ToolRunner,
    private val eventBus: BuildEventBus
) : ToolRunner {

    var buildId: String = ""

    override fun run(tool: String, args: List<String>, options: ToolOptions): ToolResult {
        eventBus.publish(ToolExecutionStartedEvent(
            buildId = buildId,
            metadata = mapOf("tool" to tool, "args" to args.joinToString(" "))
        ))

        val result = delegate.run(tool, args, options)

        eventBus.publish(ToolExecutionFinishedEvent(
            buildId = buildId,
            durationMs = result.durationMs,
            metadata = mapOf(
                "tool" to tool,
                "exitCode" to result.exitCode,
                "succeeded" to result.succeeded
            )
        ))
        return result
    }
}
