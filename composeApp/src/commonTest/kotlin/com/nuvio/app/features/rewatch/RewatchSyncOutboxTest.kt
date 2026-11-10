package com.nuvio.app.features.rewatch

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RewatchSyncOutboxTest {
    private class FakeStorage : RewatchOutboxStorage {
        val values = mutableMapOf<Int, String>()
        override suspend fun read(profileId: Int): String? = values[profileId]
        override suspend fun write(profileId: Int, payload: String) {
            values[profileId] = payload
        }
    }
    private fun event(id: String, deviceId: String = "simkl-device:a",
        revision: Long = 0) = RewatchQueuedEvent(
        eventId = id,
        deviceId = deviceId,
        canonicalSeriesId = "imdb:tt0386676",
        sessionId = "office-session",
        expectedRevision = revision,
        payload = "{\"type\":\"START\"}"
    )
    private fun outbox(storage: FakeStorage,
        current: (Int, String, String) -> Boolean = { _, _, _ -> true }) =
        RewatchSyncOutbox(storage, current)

    @Test
    fun offlineEventIsPersistedBeforeNetworkAndSurvivesRecreation() = runBlocking {
        val storage = FakeStorage()
        assertTrue(outbox(storage).enqueue(2, "12003", "simkl-device:a", event("one")))
        val rebuilt = outbox(storage)
        assertEquals(1, rebuilt.pendingCount(2, "12003", "simkl-device:a"))
        var observedPersisted = false
        assertEquals(RewatchOutboxDrainResult.DRAINED,
            rebuilt.drain(2, "12003", "simkl-device:a") {
                observedPersisted = storage.values[2]?.contains("one") == true
                RewatchOutboxSubmitResult.Accepted(1)
            }
        )
        assertTrue(observedPersisted)
        assertEquals(0, rebuilt.pendingCount(2, "12003", "simkl-device:a"))
    }

    @Test
    fun networkFailurePreservesCursorForRetry() = runBlocking {
        val storage = FakeStorage()
        val queue = outbox(storage)
        queue.enqueue(2, "12003", "simkl-device:a", event("one"))
        assertEquals(RewatchOutboxDrainResult.UNAVAILABLE,
            queue.drain(2, "12003", "simkl-device:a") {
                throw IllegalStateException("offline")
            })
        assertEquals(1, queue.pendingCount(2, "12003", "simkl-device:a"))
    }

    @Test
    fun revisionConflictDoesNotSilentlyOverwriteOrDiscard() = runBlocking {
        val storage = FakeStorage()
        val queue = outbox(storage)
        queue.enqueue(2, "12003", "simkl-device:a", event("one"))
        assertEquals(RewatchOutboxDrainResult.CONFLICT,
            queue.drain(2, "12003", "simkl-device:a") {
                RewatchOutboxSubmitResult.Conflict
            })
        assertEquals(1, queue.pendingCount(2, "12003", "simkl-device:a"))
    }

    @Test
    fun duplicateEventIdIsIdempotentButDifferentPayloadFailsSafe() = runBlocking {
        val storage = FakeStorage()
        val queue = outbox(storage)
        assertTrue(queue.enqueue(2, "12003", "simkl-device:a", event("one")))
        assertTrue(queue.enqueue(2, "12003", "simkl-device:a", event("one")))
        assertEquals(1, queue.pendingCount(2, "12003", "simkl-device:a"))
        assertFalse(queue.enqueue(2, "12003", "simkl-device:a",
            event("one").copy(payload = "{}")))
    }

    @Test
    fun accountAndDeviceRebindingNeverReplaysAnotherUsersEvents() = runBlocking {
        val storage = FakeStorage()
        val queue = outbox(storage)
        queue.enqueue(2, "12003", "simkl-device:a", event("one"))
        assertFailsWith<IllegalArgumentException> {
            queue.pendingCount(2, "99221", "simkl-device:a")
        }
        assertFailsWith<IllegalArgumentException> {
            queue.pendingCount(2, "12003", "simkl-device:b")
        }
        assertEquals(1, queue.pendingCount(2, "12003", "simkl-device:a"))
    }

    @Test
    fun profileSwitchDuringUploadRetainsEventWithoutDeletingIt() = runBlocking {
        val storage = FakeStorage()
        var account = "12003"
        val current: (Int, String, String) -> Boolean = { _, id, _ -> id == account }
        val queue = outbox(storage, current)
        queue.enqueue(2, "12003", "simkl-device:a", event("one"))
        assertEquals(RewatchOutboxDrainResult.PROFILE_CHANGED,
            queue.drain(2, "12003", "simkl-device:a") {
                account = "99221"
                RewatchOutboxSubmitResult.Accepted(1)
            })
        assertEquals(1, outbox(storage).pendingCount(2, "12003", "simkl-device:a"))
    }

    @Test
    fun uploadedEventIsRemovedOnlyAfterValidHigherServerRevision() = runBlocking {
        val storage = FakeStorage()
        val queue = outbox(storage)
        queue.enqueue(2, "12003", "simkl-device:a", event("one"))
        assertFailsWith<IllegalArgumentException> {
            queue.drain(2, "12003", "simkl-device:a") {
                RewatchOutboxSubmitResult.Accepted(0)
            }
        }
        assertEquals(1, queue.pendingCount(2, "12003", "simkl-device:a"))
    }

    @Test
    fun malformedStorageFailsClosedWithoutResettingOriginalPayload() = runBlocking {
        val storage = FakeStorage()
        storage.values[2] = "{\"schemaVersion\":9}"
        assertFailsWith<Exception> {
            outbox(storage).pendingCount(2, "12003", "simkl-device:a")
        }
        assertEquals("{\"schemaVersion\":9}", storage.values[2])
    }

    @Test
    fun unauthorizedPreservesQueuedEvents() = runBlocking {
        val storage = FakeStorage()
        val queue = outbox(storage)
        queue.enqueue(2, "12003", "simkl-device:a", event("one"))
        assertEquals(RewatchOutboxDrainResult.UNAUTHORIZED,
            queue.drain(2, "12003", "simkl-device:a") {
                RewatchOutboxSubmitResult.Unauthorized
            })
        assertEquals(1, queue.pendingCount(2, "12003", "simkl-device:a"))
    }
}
