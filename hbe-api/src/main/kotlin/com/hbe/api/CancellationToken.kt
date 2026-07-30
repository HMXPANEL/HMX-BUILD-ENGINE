package com.hbe.api

interface CancellationToken {
    val isCancelled: Boolean
    fun cancel()
    fun throwIfCancelled()
}
