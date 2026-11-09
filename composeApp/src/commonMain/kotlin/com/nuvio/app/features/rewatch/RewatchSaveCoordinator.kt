package com.nuvio.app.features.rewatch

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Owns durable replay writes independently of the player composition.
 * A new player also waits for the last write before reading the replay cursor.
 *
 * Work is launched on Main because playback completion can update Compose state.
 * The repository still enforces profile identity under its own mutex.
 */
internal class RewatchSaveQueue(private val scope: CoroutineScope) {
    private var tail: Job? = null

    fun enqueue(block: suspend () -> Unit): Job {
        val previous = tail
        return scope.launch {
            previous?.join()
            block()
        }.also { tail = it }
    }

    suspend fun awaitPending() {
        tail?.join()
    }
}

internal object RewatchSaveCoordinator {
    private val queue = RewatchSaveQueue(
        CoroutineScope(SupervisorJob() + Dispatchers.Main)
    )

    fun enqueue(block: suspend () -> Unit): Job = queue.enqueue(block)

    suspend fun awaitPending() = queue.awaitPending()
}
