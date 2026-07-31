package com.hbe.core.event

import com.hbe.api.event.BuildFinishedEvent
import com.hbe.api.event.BuildStartedEvent
import com.hbe.api.event.ToolExecutionStartedEvent
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class InMemoryBuildEventBusTest {

    @Test
    fun `publish delivers to subscribers`() {
        val bus = InMemoryBuildEventBus()
        val received = mutableListOf<com.hbe.api.event.BuildEvent>()
        bus.subscribe { received.add(it) }

        bus.publish(BuildStartedEvent(buildId = "b1"))

        assertEquals(1, received.size)
        assertTrue(received.first() is BuildStartedEvent)
    }

    @Test
    fun `typed subscription filters events`() {
        val bus = InMemoryBuildEventBus()
        val finished = mutableListOf<BuildFinishedEvent>()
        bus.subscribe(BuildFinishedEvent::class.java) { finished.add(it) }

        bus.publish(BuildStartedEvent(buildId = "b1"))
        bus.publish(BuildFinishedEvent(buildId = "b1", durationMs = 42))

        assertEquals(1, finished.size)
        assertEquals(42, finished.first().durationMs)
    }

    @Test
    fun `unsubscribe stops delivery`() {
        val bus = InMemoryBuildEventBus()
        val received = mutableListOf<com.hbe.api.event.BuildEvent>()
        val unsubscribe = bus.subscribe { received.add(it) }

        unsubscribe()
        bus.publish(BuildStartedEvent(buildId = "b1"))

        assertTrue(received.isEmpty())
    }

    @Test
    fun `listener exception does not break publish`() {
        val bus = InMemoryBuildEventBus()
        bus.subscribe { throw RuntimeException("listener failure") }
        bus.subscribe { throw RuntimeException("another failure") }

        assertDoesNotThrow { bus.publish(BuildStartedEvent(buildId = "b1")) }
    }

    @Test
    fun `clear removes all subscribers`() {
        val bus = InMemoryBuildEventBus()
        bus.subscribe { }
        assertTrue(bus.hasSubscribers())

        bus.clear()

        assertFalse(bus.hasSubscribers())
    }

    @Test
    fun `event carries phase and metadata`() {
        val event = ToolExecutionStartedEvent(
            buildId = "b1",
            metadata = mapOf("tool" to "aapt2")
        )
        assertEquals("TOOL", event.phase)
        assertEquals("aapt2", event.metadata["tool"])
        assertTrue(event.timestamp > 0)
    }
}
