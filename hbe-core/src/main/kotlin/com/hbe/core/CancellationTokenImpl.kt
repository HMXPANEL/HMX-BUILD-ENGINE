package com.hbe.core

import com.hbe.api.CancellationToken
import java.util.concurrent.atomic.AtomicBoolean

class CancellationTokenImpl : CancellationToken {
    private val cancelled = AtomicBoolean(false)

    override val isCancelled: Boolean get() = cancelled.get()

    override fun cancel() {
        cancelled.set(true)
    }

    override fun throwIfCancelled() {
        if (cancelled.get()) {
            throw BuildCancelledException()
        }
    }
}

class BuildCancelledException : RuntimeException("Build was cancelled")
