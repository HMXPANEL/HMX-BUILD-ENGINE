package com.hbe.core.event

import com.hbe.api.ToolOptions
import com.hbe.api.ToolResult
import com.hbe.api.ToolRunner
import com.hbe.api.event.BuildEvent
import com.hbe.api.event.ToolExecutionFinishedEvent
import com.hbe.api.event.ToolExecutionStartedEvent
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class EventEmittingToolRunnerTest {

    @Test
    fun `emits started and finished events with tool metadata`() {
        val delegate = mockk<ToolRunner>()
        every { delegate.run("aapt2", listOf("version"), any()) } returns ToolResult(0, "", "", 42, true)

        val bus = InMemoryBuildEventBus()
        val events = mutableListOf<BuildEvent>()
        bus.subscribe { events.add(it) }
        val runner = EventEmittingToolRunner(delegate, bus)
        runner.buildId = "bld-1"

        val result = runner.run("aapt2", listOf("version"), ToolOptions())

        assertTrue(result.succeeded)
        assertEquals(2, events.size)
        val started = events[0] as ToolExecutionStartedEvent
        val finished = events[1] as ToolExecutionFinishedEvent
        assertEquals("bld-1", started.buildId)
        assertEquals("aapt2", started.metadata["tool"])
        assertEquals("bld-1", finished.buildId)
        assertEquals(42, finished.durationMs)
    }
}
