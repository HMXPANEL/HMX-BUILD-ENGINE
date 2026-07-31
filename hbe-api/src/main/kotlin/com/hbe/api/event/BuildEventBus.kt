package com.hbe.api.event

interface BuildEventBus {
    fun publish(event: BuildEvent)

    fun subscribe(listener: (BuildEvent) -> Unit): () -> Unit

    fun <T : BuildEvent> subscribe(type: Class<T>, listener: (T) -> Unit): () -> Unit

    fun clear()

    fun hasSubscribers(): Boolean
}
