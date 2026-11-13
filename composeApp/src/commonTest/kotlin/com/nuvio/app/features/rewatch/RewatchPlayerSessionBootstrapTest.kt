package com.nuvio.app.features.rewatch

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class RewatchPlayerSessionBootstrapTest {
    private val episode = RewatchEpisode(3, 5)
    private val profileId = 2
    private val owner = "local-profile:2"
    private val series = "imdb:tt0386676"

    private class FakeStore : RewatchSessionAccess {
        val sessions = mutableMapOf<Int, List<RewatchSession>>()
        var acceptWrites = true
        var writes = 0

        override suspend fun load(profileId: Int) =
            sessions[profileId].orEmpty()

        override suspend fun upsert(
            profileId: Int,
            session: RewatchSession,
            replaceSession: Boolean
        ): Boolean {
            if (!acceptWrites) return false
            val updated = mergeRewatchSessionUpdate(
                sessions[profileId].orEmpty(),
                session,
                replaceSession
            ) ?: return false
            sessions[profileId] = updated
            writes++
            return true
        }
    }

    private suspend fun bootstrap(
        store: FakeStore,
        activeProfile: () -> Int = { profileId },
        watched: suspend () -> Boolean = { true },
        requestedEpisode: RewatchEpisode = episode,
        now: Long = 2_000L,
        explicitManualSelection: Boolean = false,
        onCommitted: suspend (RewatchSession?, RewatchSession) -> Unit = { _, _ -> },
    ) = resolveOrStartRewatchForPlayback(
        store = store,
        expectedProfileId = profileId,
        activeProfileId = activeProfile,
        ownerKey = owner,
        canonicalSeriesId = series,
        episode = requestedEpisode,
        wasPreviouslyWatched = watched,
        createSessionId = { "replay-2" },
        nowEpochMs = { now },
        explicitManualEpisodeSelection = explicitManualSelection,
        onCommitted = onCommitted
    )

    private fun stored(
        phase: RewatchPhase = RewatchPhase.PLAYING,
        selectedEpisode: RewatchEpisode = episode
    ) = RewatchSession(
        ownerKey = owner,
        canonicalSeriesId = series,
        sessionId = "replay-1",
        episode = selectedEpisode,
        positionMs = 90_000L,
        durationMs = 600_000L,
        lastCompletedEpisode = RewatchEpisode(3, 4),
        phase = phase,
        updatedAtEpochMs = 1_000L
    )

    @Test
    fun firstWatchDoesNotCreateReplayOrChangeHistory() = runTest {
        val store = FakeStore()
        val result = bootstrap(store, watched = { false })
        assertNull(result)
        assertEquals(0, store.writes)
    }

    @Test
    fun positivelyWatchedEpisodeStartsIsolatedReplay() = runTest {
        val store = FakeStore()
        val created = assertNotNull(bootstrap(store))
        assertEquals("replay-2", created.sessionId)
        assertEquals(episode, created.episode)
        assertEquals(RewatchPhase.PLAYING, created.phase)
        assertEquals(created, store.sessions[profileId]?.single())
        assertEquals(1, store.writes)
        assertTrue(store.sessions[1].isNullOrEmpty())
    }

    @Test
    fun existingReplayResumesWithoutAnotherProviderLookup() = runTest {
        val store = FakeStore()
        val session = stored(RewatchPhase.NEXT_UP)
        store.sessions[profileId] = listOf(session)
        val resumed = bootstrap(store, watched = {
            error("Previously established replay must not consult historical flags")
        })
        assertEquals(session, resumed)
        assertEquals(0, store.writes)
    }

    @Test
    fun selectingUnrelatedEpisodeCannotReplaceActiveReplay() = runTest {
        val store = FakeStore()
        store.sessions[profileId] = listOf(stored())
        val result = bootstrap(
            store,
            requestedEpisode = RewatchEpisode(3, 6)
        )
        assertNull(result)
        assertEquals(0, store.writes)
    }

    @Test
    fun deliberateForwardEpisodeJumpRepositionsSameReplayWithoutChangingSimkl() = runTest {
        val store = FakeStore()
        val previous = stored(RewatchPhase.NEXT_UP)
        store.sessions[profileId] = listOf(previous)
        val commits = mutableListOf<Pair<RewatchSession?, RewatchSession>>()
        val selected = RewatchEpisode(5, 18)
        val updated = assertNotNull(bootstrap(
            store,
            requestedEpisode = selected,
            explicitManualSelection = true,
            watched = { true },
            onCommitted = { before, after -> commits += before to after }
        ))
        assertEquals(previous.sessionId, updated.sessionId)
        assertEquals(selected, updated.episode)
        assertEquals(RewatchPhase.PLAYING, updated.phase)
        assertEquals(0L, updated.positionMs)
        assertEquals(0L, updated.durationMs)
        assertNull(updated.lastCompletedEpisode)
        assertEquals(1, store.writes)
        assertEquals(previous, commits.single().first)
        assertEquals(updated, commits.single().second)
    }

    @Test
    fun explicitBackwardJumpRequiresWatchedProofAndFreshClock() = runTest {
        val store = FakeStore()
        val existing = stored(RewatchPhase.PLAYING)
        store.sessions[profileId] = listOf(existing)
        val earlier = RewatchEpisode(2, 1)
        assertNull(bootstrap(store,
            requestedEpisode = earlier,
            explicitManualSelection = true,
            watched = { false }
        ))
        assertNull(bootstrap(store,
            requestedEpisode = earlier,
            explicitManualSelection = true,
            watched = { true },
            now = existing.updatedAtEpochMs
        ))
        assertEquals(0, store.writes)
        val moved = assertNotNull(bootstrap(store,
            requestedEpisode = earlier,
            explicitManualSelection = true,
            watched = { true }
        ))
        assertEquals(earlier, moved.episode)
        assertEquals(existing.sessionId, moved.sessionId)
    }

    @Test
    fun finishedReplayCanStartNewSession() = runTest {
        val store = FakeStore()
        store.sessions[profileId] = listOf(stored(RewatchPhase.FINISHED))
        val created = assertNotNull(bootstrap(store))
        assertEquals("replay-2", created.sessionId)
        assertEquals(1, store.writes)
    }

    @Test
    fun profileSwitchBeforeOrDuringWatchedCheckPreventsWrite() = runTest {
        val store = FakeStore()
        assertNull(bootstrap(store, activeProfile = { 1 }))
        var active = profileId
        assertNull(bootstrap(store, activeProfile = { active }, watched = {
            active = 1
            true
        }))
        assertEquals(0, store.writes)
    }

    @Test
    fun failedWriteDoesNotAdvertiseAnActiveReplay() = runTest {
        val store = FakeStore().apply { acceptWrites = false }
        assertNull(bootstrap(store))
        assertEquals(0, store.writes)
    }

    @Test
    fun staleClockCannotReplaceNewerFinishedReplay() = runTest {
        val store = FakeStore()
        store.sessions[profileId] = listOf(
            stored(RewatchPhase.FINISHED).copy(updatedAtEpochMs = 3_000L)
        )
        assertNull(bootstrap(store, now = 2_000L))
        assertEquals(0, store.writes)
    }
}
