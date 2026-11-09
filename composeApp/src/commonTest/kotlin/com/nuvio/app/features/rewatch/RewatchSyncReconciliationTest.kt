package com.nuvio.app.features.rewatch

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class RewatchSyncReconciliationTest {
    private fun session(
        owner: String = "local-profile:2",
        series: String = "tt0386676",
        phase: RewatchPhase = RewatchPhase.NEXT_UP,
        episode: Int = 6
    ) = RewatchSession(
        ownerKey = owner,
        canonicalSeriesId = series,
        sessionId = "replay-1",
        episode = RewatchEpisode(3, episode),
        positionMs = 0L,
        durationMs = 0L,
        lastCompletedEpisode = RewatchEpisode(3, 5),
        phase = phase,
        updatedAtEpochMs = 1000L
    )

    @Test
    fun newerServerRevisionReplacesCleanLocalCursor() {
        val newer = RemoteRewatchSnapshot(3, session(episode = 7))
        assertEquals(
            RewatchSyncDecision.ApplyRemote(newer),
            reconcileRewatchSnapshot(session(), newer, 2, false)
        )
    }

    @Test
    fun offlineEpisodeCompletionNeverSilentlyLosesToServer() {
        val remote = RemoteRewatchSnapshot(4, session())
        assertEquals(
            RewatchSyncDecision.Conflict(remote),
            reconcileRewatchSnapshot(session(episode = 7), remote, 3, true)
        )
    }

    @Test
    fun remoteTombstonePreventsProviderCardResurrection() {
        val stopped = RemoteRewatchSnapshot(
            5, session(phase = RewatchPhase.FINISHED)
        )
        val decision = reconcileRewatchSnapshot(session(), stopped, 4, false)
        assertIs<RewatchSyncDecision.ApplyRemote>(decision)
        assertEquals(RewatchPhase.FINISHED, decision.snapshot.session.phase)
    }

    @Test
    fun staleServerResponseDoesNotRewindCursor() {
        val old = RemoteRewatchSnapshot(2, session())
        assertEquals(
            RewatchSyncDecision.KeepLocal,
            reconcileRewatchSnapshot(session(episode = 7), old, 3, false)
        )
    }

    @Test
    fun mismatchedOwnerOrSeriesNeverApplies() {
        val local = session()
        val otherProfile = RemoteRewatchSnapshot(4, session(owner = "local-profile:1"))
        val otherShow = RemoteRewatchSnapshot(4, session(series = "tmdb:other"))
        assertIs<RewatchSyncDecision.Conflict>(
            reconcileRewatchSnapshot(local, otherProfile, 3, false)
        )
        assertIs<RewatchSyncDecision.Conflict>(
            reconcileRewatchSnapshot(local, otherShow, 3, false)
        )
    }

    @Test
    fun noServerSnapshotLeavesLocalStateIntact() {
        assertEquals(
            RewatchSyncDecision.KeepLocal,
            reconcileRewatchSnapshot(session(), null, 0, true)
        )
    }
}
