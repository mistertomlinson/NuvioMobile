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
