package com.nuvio.app.features.rewatch

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking

class RewatchSaveCoordinatorTest {
    @Test
    fun episodeHandoffWaitsUntilPreviousDurableSaveCompletes() = runBlocking {
        val workScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val releaseFirst = CompletableDeferred<Unit>()
            val events = mutableListOf<String>()
            val queue = RewatchSaveQueue(workScope)
            val first = queue.enqueue {
                events += "save-episode-5-start"
                releaseFirst.await()
                events += "save-episode-5-end"
            }
            val second = queue.enqueue { events += "save-episode-6" }
            assertEquals(listOf("save-episode-5-start"), events)
            releaseFirst.complete(Unit)
            joinAll(first, second)
            queue.awaitPending()
            assertEquals(
                listOf("save-episode-5-start", "save-episode-5-end", "save-episode-6"),
                events
            )
        } finally {
            workScope.cancel()
        }
    }

    @Test
    fun playerScopeCancellationDoesNotDiscardQueuedReplaySave() = runBlocking {
        val workScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val playerScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val queue = RewatchSaveQueue(workScope)
            val allowCommit = CompletableDeferred<Unit>()
            var committed = false
            val save = queue.enqueue {
                allowCommit.await()
                committed = true
            }
            playerScope.cancel() // Mirrors navigating away from the player.
            allowCommit.complete(Unit)
            save.join()
            assertEquals(true, committed)
        } finally {
            playerScope.cancel()
            workScope.cancel()
        }
    }
}
