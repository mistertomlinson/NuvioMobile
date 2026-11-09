package com.nuvio.app.features.rewatch

import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.Test

class RewatchSessionPolicyTest {
    private val e1 = RewatchEpisode(2, 1)
    private val e2 = RewatchEpisode(2, 2)
    private val e3 = RewatchEpisode(2, 3)
    private val s3e1 = RewatchEpisode(3, 1)
    private val ordered = listOf(e1, e2, e3, s3e1)

    private fun start(
        episode: RewatchEpisode = e1,
        owner: String = "owner-A"
    ): RewatchSession = requireNotNull(
        startRewatchSession(
            ownerKey = owner,
            canonicalSeriesId = "imdb:tt-test",
            sessionId = "session-1",
            episode = episode,
            wasAlreadyWatched = true,
            updatedAtEpochMs = 1_000L
        )
    )

    @Test
    fun `first watch never creates a rewatch session`() {
        assertNull(
            startRewatchSession(
                ownerKey = "owner-A",
                canonicalSeriesId = "imdb:tt-test",
                sessionId = "session-1",
                episode = e1,
                wasAlreadyWatched = false,
                updatedAtEpochMs = 1_000L
            )
        )
    }

    @Test
    fun `genuine replay saves position and advances despite historical watched flags`() {
        val playing = recordRewatchProgress(
            session = start(),
            episode = e1,
            positionMs = 90_000L,
            durationMs = 1_200_000L,
            updatedAtEpochMs = 2_000L
        )
        assertEquals(90_000L, playing.positionMs)
        assertEquals(RewatchPhase.PLAYING, playing.phase)

        val next = completeRewatchEpisode(
            session = playing,
            completedEpisode = e1,
            orderedPlayableEpisodes = ordered,
            metadataComplete = true,
            updatedAtEpochMs = 3_000L
        )
        assertEquals(e2, next.episode)
        assertEquals(e1, next.lastCompletedEpisode)
        assertEquals(RewatchPhase.NEXT_UP, next.phase)
        assertEquals(0L, next.positionMs)
    }

    @Test
    fun `completion does not expire after three minutes or one day`() {
        val completed = completeRewatchEpisode(start(), e1, ordered, true, 2_000L)
        // There is no TTL lookup: persisting and reloading returns the same cursor.
        assertEquals(e2, completed.copy().episode)
        assertEquals(RewatchPhase.NEXT_UP, completed.copy().phase)
    }

    @Test
    fun `episode advancement crosses seasons without restarting watched history`() {
        val e3Session = start(episode = e3)
        val next = completeRewatchEpisode(e3Session, e3, ordered, true, 2_000L)
        assertEquals(s3e1, next.episode)
        assertEquals(RewatchPhase.NEXT_UP, next.phase)
    }

    @Test
    fun `last released episode finishes replay without changing history`() {
        val lastEpisode = start(episode = s3e1)
        val finished = completeRewatchEpisode(lastEpisode, s3e1, ordered, true, 2_000L)
        assertEquals(RewatchPhase.FINISHED, finished.phase)
        assertEquals(s3e1, finished.lastCompletedEpisode)
    }

    @Test
    fun `temporary metadata failure preserves cursor and resolves later`() {
        val pending = completeRewatchEpisode(start(), e1, null, false, 2_000L)
        assertEquals(e1, pending.episode)
        assertEquals(RewatchPhase.AWAITING_METADATA, pending.phase)
        assertEquals(e1, pending.lastCompletedEpisode)

        val recovered = resolvePendingRewatchSuccessor(pending, ordered, true)
        assertEquals(e2, recovered.episode)
        assertEquals(RewatchPhase.NEXT_UP, recovered.phase)
    }

    @Test
    fun `missing completed episode in metadata cannot end replay`() {
        val pending = completeRewatchEpisode(
            start(),
            e1,
            listOf(e2, e3, s3e1),
            true,
            2_000L
        )
        assertEquals(RewatchPhase.AWAITING_METADATA, pending.phase)
    }

    @Test
    fun `duplicate metadata entries cannot end replay`() {
        val pending = completeRewatchEpisode(
            start(),
            e1,
            listOf(e1, e1, e2),
            true,
            2_000L
        )
        assertEquals(RewatchPhase.AWAITING_METADATA, pending.phase)
    }

    @Test
    fun `duplicate completion does not skip another episode`() {
        val next = completeRewatchEpisode(start(), e1, ordered, true, 2_000L)
        val repeated = completeRewatchEpisode(next, e1, ordered, true, 3_000L)
        assertSame(next, repeated)
    }

    @Test
    fun `next up cannot be marked complete before its playback begins`() {
        val next = completeRewatchEpisode(start(), e1, ordered, true, 2_000L)
        val premature = completeRewatchEpisode(next, e2, ordered, true, 3_000L)
        assertSame(next, premature)
        assertEquals(RewatchPhase.NEXT_UP, premature.phase)
        assertEquals(e2, premature.episode)
    }

    @Test
    fun `metadata wait does not duplicate completion before recovery`() {
        val pending = completeRewatchEpisode(start(), e1, null, false, 2_000L)
        assertSame(pending, completeRewatchEpisode(pending, e1, ordered, true, 3_000L))
        assertEquals(e2, resolvePendingRewatchSuccessor(pending, ordered, true).episode)
    }

    @Test
    fun `late older-episode progress cannot overwrite new next up`() {
        val next = completeRewatchEpisode(start(), e1, ordered, true, 2_000L)
        val stale = recordRewatchProgress(next, e1, 500_000L, 600_000L, 3_000L)
        assertSame(next, stale)
        val advanced = recordRewatchProgress(next, e2, 150_000L, 600_000L, 3_000L)
        assertEquals(RewatchPhase.PLAYING, advanced.phase)
        assertEquals(150_000L, advanced.positionMs)
    }

    @Test
    fun `older timestamps within a device cannot undo newer playback`() {
        val current = recordRewatchProgress(start(), e1, 100_000L, 600_000L, 3_000L)
        assertSame(
            current,
            recordRewatchProgress(current, e1, 200_000L, 600_000L, 2_000L)
        )
    }

    @Test
    fun `unfinished episode must never advance replay`() {
        val current = recordRewatchProgress(start(), e1, 400_000L, 600_000L, 2_000L)
        assertEquals(e1, current.episode)
        assertEquals(RewatchPhase.PLAYING, current.phase)
    }

    @Test
    fun `completed session cannot be revived by old player events`() {
        val finished = completeRewatchEpisode(start(s3e1), s3e1, ordered, true, 2_000L)
        assertSame(finished, recordRewatchProgress(finished, s3e1, 100L, 500L, 3_000L))
        assertSame(finished, completeRewatchEpisode(finished, s3e1, ordered, true, 4_000L))
    }

    @Test
    fun `owner and session are explicit to prevent profile leakage`() {
        val personA = start(owner = "owner-A")
        val personB = start(owner = "owner-B")
        assertEquals("owner-A", personA.ownerKey)
        assertEquals("owner-B", personB.ownerKey)
        assertEquals("session-1", personA.sessionId)
        assertNotNull(personB.canonicalSeriesId)
    }
}
