package com.nuvio.app.features.rewatch

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RewatchOfflineRevisionParityTest {
    private val account = "12003"
    private val device = "simkl-device:ios-ipad"
    private val initial = requireNotNull(startRewatchSession(
        ownerKey = "local-profile:2",
        canonicalSeriesId = "imdb:tt0386676",
        sessionId = "ios-office-replay",
        episode = RewatchEpisode(3, 18),
        wasAlreadyWatched = true,
        updatedAtEpochMs = 100L
    ))
    private class Memory : RewatchOutboxStorage {
        private val values = mutableMapOf<Int, String>()
        override suspend fun read(profileId: Int) = values[profileId]
        override suspend fun write(profileId: Int, payload: String) {
            values[profileId] = payload
        }
    }
    private fun queue(store: Memory) = RewatchSyncOutbox(store) { _, _, _ -> true }

    @Test fun offlineProgressAndSuccessorProduceContinuousRevisions() = runBlocking {
        val store = Memory()
        val q = queue(store)
        val progress = initial.copy(
            positionMs = 15_000L, durationMs = 1_500_000L,
            updatedAtEpochMs = 200L
        )
        val next = progress.copy(
            episode = RewatchEpisode(3, 19),
            lastCompletedEpisode = RewatchEpisode(3, 18),
            phase = RewatchPhase.NEXT_UP,
            positionMs = 0L, durationMs = 0L,
            updatedAtEpochMs = 300L
        )
        assertTrue(q.enqueueCommittedTransition(
            2, account, device, null, initial,
            RewatchRemoteCheckpoint.ConfirmedEmpty, "ios-start"
        ))
        assertTrue(q.enqueueCommittedTransition(
            2, account, device, initial, progress,
            RewatchRemoteCheckpoint.ConfirmedEmpty, "ios-progress"
        ))
        assertTrue(q.enqueueCommittedTransition(
            2, account, device, progress, next,
            RewatchRemoteCheckpoint.ConfirmedEmpty, "ios-complete"
        ))
        assertEquals(3, queue(store).pendingCount(2, account, device))
        val revisions = mutableListOf<Long>()
        assertEquals(RewatchOutboxDrainResult.DRAINED,
            queue(store).drain(2, account, device) { event ->
                revisions += event.expectedRevision
                RewatchOutboxSubmitResult.Accepted(event.expectedRevision + 1L)
            })
        assertEquals(listOf(0L, 1L, 2L), revisions)
        assertEquals(3L, queue(store).acknowledgedCheckpoint(
            2, account, device, initial.canonicalSeriesId
        )?.revision)
        assertEquals(device, queue(store).knownDeviceIdForAccount(2, account))
        assertEquals(null, queue(store).knownDeviceIdForAccount(2, "99221"))
    }

    @Test fun afterRestartOfflineCanContinueFromLastAckWithoutNetwork() = runBlocking {
        val store = Memory()
        val q = queue(store)
        q.enqueueCommittedTransition(2, account, device,
            null, initial, RewatchRemoteCheckpoint.ConfirmedEmpty, "ios-import")
        q.drain(2, account, device) { event ->
            RewatchOutboxSubmitResult.Accepted(event.expectedRevision + 1)
        }
        val progressed = initial.copy(
            positionMs = 10_000, durationMs = 1_200_000,
            updatedAtEpochMs = 200
        )
        val restarted = queue(store)
        assertTrue(restarted.enqueueCommittedTransition(
            2, account, device, initial, progressed,
            RewatchRemoteCheckpoint.Unknown, "ios-offline"
        ))
        assertEquals(RewatchOutboxDrainResult.UNAVAILABLE,
            restarted.drain(2, account, device) { event ->
                assertEquals(1L, event.expectedRevision)
                RewatchOutboxSubmitResult.Unavailable
            })
        assertEquals(1, restarted.pendingCount(2, account, device))
    }

    @Test fun authenticatedMatchingRemoteGetAdvancesRevisionButMismatchNeverDoes() = runBlocking {
        val store = Memory()
        val q = queue(store)
        val matched = RewatchRemoteLookup.Verified(
            RewatchRemoteCheckpoint.Synced(9, initial.sessionId),
            initial.copy(updatedAtEpochMs = 0)
        )
        assertTrue(q.recordVerifiedCleanBaseline(
            2, account, device, initial, matched
        ))
        assertEquals(9L, queue(store).acknowledgedCheckpoint(
            2, account, device, initial.canonicalSeriesId
        )?.revision)
        val mismatched = RewatchRemoteLookup.Verified(
            RewatchRemoteCheckpoint.Synced(10, initial.sessionId),
            initial.copy(positionMs = 40_000, updatedAtEpochMs = 0)
        )
        assertFalse(q.recordVerifiedCleanBaseline(
            2, account, device, initial, mismatched
        ))
        assertEquals(9L, queue(store).acknowledgedCheckpoint(
            2, account, device, initial.canonicalSeriesId
        )?.revision)
    }

    @Test fun crossProfilePendingWritesPreventRemoteCleanProof() = runBlocking {
        val store = Memory()
        val q = queue(store)
        q.enqueueCommittedTransition(2, account, device,
            null, initial, RewatchRemoteCheckpoint.ConfirmedEmpty, "ios-import")
        assertTrue(q.hasPendingForSeries(2, account, device, initial.canonicalSeriesId))
        assertFalse(q.recordVerifiedCleanBaseline(
            2, account, device, initial,
            RewatchRemoteLookup.Verified(
                RewatchRemoteCheckpoint.Synced(7, initial.sessionId),
                initial.copy(updatedAtEpochMs = 0)
            )
        ))
    }
    @Test fun pendingSeriesRosterIsStableAcrossRestart() = runBlocking {
        val store = Memory()
        val q = queue(store)
        assertTrue(q.enqueueCommittedTransition(
            2, account, device, null, initial,
            RewatchRemoteCheckpoint.ConfirmedEmpty, "episode-import"
        ))
        assertEquals(
            setOf("imdb:tt0386676"),
            queue(store).pendingSeriesIds(2, account, device)
        )
    }

}
