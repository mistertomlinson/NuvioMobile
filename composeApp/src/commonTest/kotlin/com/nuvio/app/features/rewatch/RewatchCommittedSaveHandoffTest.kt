package com.nuvio.app.features.rewatch

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RewatchCommittedSaveHandoffTest {
    private val replay = requireNotNull(startRewatchSession(
        ownerKey = "local-profile:2",
        canonicalSeriesId = "imdb:tt0386676",
        sessionId = "office-ipad",
        episode = RewatchEpisode(3, 18),
        wasAlreadyWatched = true,
        updatedAtEpochMs = 100
    ))

    @Test fun isolatedIpadDeviceTestEnablesReplayOnlySyncButDoesNotAutoInstallSink() {
        // Only this separate device-test branch has the gate enabled.
        // Playback's remote handoff must still be installed intentionally.
        assertTrue(RewatchMobileSyncGate.ENABLED)
        assertTrue(RewatchIpadCommitHandoff.sink === DisabledRewatchCommittedSaveSink)
    }

    @Test fun onlyAcceptedLocalSaveReachesEventSink() = runBlocking {
        val calls = mutableListOf<Pair<RewatchSession?, RewatchSession>>()
        val sink = RewatchCommittedSaveSink { _, before, after ->
            calls += before to after
        }
        val progressed = replay.copy(positionMs = 5000, updatedAtEpochMs = 200)
        notifyCommittedRewatchSave(sink, false, 2, { 2 }, null, replay)
        notifyCommittedRewatchSave(sink, true, 2, { 2 }, replay, progressed)
        assertEquals(1, calls.size)
        assertEquals(replay, calls.single().first)
        assertEquals(progressed, calls.single().second)
    }

    @Test fun onlyVerifiedManualSelectionCanEnterRepositionOutbox() = runBlocking {
        val saved = mutableMapOf<Int, String>()
        val storage = object : RewatchOutboxStorage {
            override suspend fun read(profileId: Int) = saved[profileId]
            override suspend fun write(profileId: Int, payload: String) {
                saved[profileId] = payload
            }
        }
        val outbox = RewatchSyncOutbox(storage) { profileId, accountId, deviceId ->
            profileId == 2 && accountId == "12003" &&
                deviceId == "simkl-device:manual-test"
        }
        val sink = RewatchCommittedOutboxSink(
            currentIdentity = {
                RewatchCommitIdentity(2, "12003", "simkl-device:manual-test")
            },
            checkpoint = { _, _ ->
                RewatchRemoteCheckpoint.Synced(3, replay.sessionId)
            },
            outbox = outbox,
            eventId = { "manual-reposition-1" }
        )
        val moved = replay.copy(
            episode = RewatchEpisode(5, 19),
            positionMs = 391_000L,
            durationMs = 0L,
            lastCompletedEpisode = null,
            phase = RewatchPhase.PLAYING,
            updatedAtEpochMs = 200L
        )
        notifyCommittedRewatchSave(
            sink, true, 2, { 2 }, replay, moved
        )
        assertEquals(0, outbox.pendingCount(2, "12003", "simkl-device:manual-test"))
        notifyCommittedRewatchSave(
            sink, true, 2, { 2 }, replay, moved,
            manualEpisodeSelection = true
        )
        assertEquals(1, outbox.pendingCount(2, "12003", "simkl-device:manual-test"))
        assertTrue(saved[2]?.contains("REPOSITION") == true)
    }

    @Test fun accountProfileChangeBlocksStaleCommittedReplay() = runBlocking {
        var calls = 0
        val sink = RewatchCommittedSaveSink { _, _, _ -> calls++ }
        notifyCommittedRewatchSave(sink, true, 2, { 3 }, null, replay)
        notifyCommittedRewatchSave(
            sink, true, 2, { 2 }, null,
            replay.copy(ownerKey = "local-profile:3")
        )
        assertEquals(0, calls)
    }

    @Test fun remoteQueueErrorDoesNotTurnReplayIntoSimklHistoryMutation() = runBlocking {
        var calls = 0
        notifyCommittedRewatchSave(
            RewatchCommittedSaveSink { _, _, _ ->
                calls++
                error("offline")
            },
            true, 2, { 2 }, null, replay
        )
        assertEquals(1, calls)
    }

    @Test fun metadataCompletionToNextUpIsPassedAsSingleCommittedTransition() = runBlocking {
        val observed = mutableListOf<RewatchSession>()
        val next = replay.copy(
            episode = RewatchEpisode(3, 19),
            lastCompletedEpisode = RewatchEpisode(3, 18),
            phase = RewatchPhase.NEXT_UP,
            updatedAtEpochMs = 200
        )
        notifyCommittedRewatchSave(
            RewatchCommittedSaveSink { _, _, after -> observed += after },
            true, 2, { 2 }, replay, next
        )
        assertEquals(RewatchEpisode(3, 19), observed.single().episode)
    }
}
