package com.boldexplorer.shared.output

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Single entry point feature code calls to produce output — see issue #11. Backed by a bounded
 * [Channel], not a `SharedFlow`: a `SharedFlow` broadcasts to every collector, and nothing would
 * stop a second collector from double-speaking/double-logging every event. A `Channel` is
 * single-consumer by construction, and [startConsuming] enforces that only one consumer ever runs,
 * so the guarantee lives here rather than as a convention the router has to uphold.
 */
class OutputManager(
    channelCapacity: Int = 64,
) {
    private val channel = Channel<OutputEvent>(capacity = channelCapacity)
    private var consuming = false

    /**
     * Non-suspending. Documented overflow behavior: if the bounded buffer is ever full (should
     * never happen at this app's event rate — a handful of events per minute), the newest event
     * is silently dropped rather than blocking the caller. `:shared` has no logging framework
     * today; revisit if dropped events ever become an observed problem.
     */
    fun emit(event: OutputEvent) {
        channel.trySend(event)
    }

    /** Must be called exactly once, by the single router. Returns false if already started. */
    fun startConsuming(
        scope: CoroutineScope,
        onEvent: suspend (OutputEvent) -> Unit,
    ): Boolean {
        if (consuming) return false
        consuming = true
        scope.launch {
            for (event in channel) {
                try {
                    onEvent(event)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Isolated per-event failure — must not kill the loop for subsequent events.
                }
            }
        }
        return true
    }
}
