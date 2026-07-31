package com.hbe.core.event

import com.hbe.api.event.BuildEvent
import com.hbe.api.event.BuildEventBus
import java.util.concurrent.CopyOnWriteArrayList

class InMemoryBuildEventBus : BuildEventBus {

    private val listeners = CopyOnWriteArrayList<(BuildEvent) -> Unit>()

    override fun publish(event: BuildEvent) {
        for (listener in listeners) {
            try {
                listener(event)
            } catch (_: Exception) {
                // A listener must never break the build
            }
        }
    }

    override fun subscribe(listener: (BuildEvent) -> Unit): () -> Unit {
        listeners.add(listener)
        return { listeners.remove(listener) }
    }

    override fun <T : BuildEvent> subscribe(type: Class<T>, listener: (T) -> Unit): () -> Unit {
        return subscribe { event ->
            if (type.isInstance(event)) {
                listener(type.cast(event))
            }
        }
    }

    override fun clear() {
        listeners.clear()
    }

    override fun hasSubscribers(): Boolean = listeners.isNotEmpty()
}
