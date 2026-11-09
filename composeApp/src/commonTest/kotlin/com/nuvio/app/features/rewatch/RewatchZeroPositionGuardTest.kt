package com.nuvio.app.features.rewatch

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class RewatchZeroPositionGuardTest {
    private val e1 = RewatchEpisode(1, 1)
    private val e2 = RewatchEpisode(1, 2)
    private val e3 = RewatchEpisode(1, 3)
    private val ordered = listOf(e1, e2, e3)

    private fun started() = requireNotNull(
        startRewatchSession(
            ownerKey = "wife",
            canonicalSeriesId = "imdb:tt0386676",
            sessionId = "rewatch-1",
            episode = e1,
            wasAlreadyWatched = true,
            updatedAtEpochMs = 1000L
        )
    )

    @Test
    fun nextUpZeroPositionDoesNotArmASecondCompletion() {
        val queued = completeRewatchEpisode(
            started(), e1, ordered, true, 2000L
        )
        val zero = recordRewatchProgress(
            queued, e2, 0L, 120_000L, 3000L
        )
        assertSame(queued, zero)
        assertEquals(
            RewatchPhase.NEXT_UP,
            completeRewatchEpisode(zero, e2, ordered, true, 4000L).phase
        )
    }

    @Test
    fun meaningfulPlaybackActivatesTheSuccessorBeforeCompletion() {
        val queued = completeRewatchEpisode(
            started(), e1, ordered, true, 2000L
        )
        val playing = recordRewatchProgress(
            queued, e2, 1200L, 120_000L, 3000L
        )
        assertEquals(RewatchPhase.PLAYING, playing.phase)
        val next = completeRewatchEpisode(
            playing, e2, ordered, true, 4000L
        )
        assertEquals(e3, next.episode)
        assertEquals(RewatchPhase.NEXT_UP, next.phase)
    }
}
