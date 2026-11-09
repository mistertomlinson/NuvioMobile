package com.nuvio.app.features.rewatch

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RewatchResumePolicyTest {
    private val episode = RewatchEpisode(3, 5)

    private fun session(
        phase: RewatchPhase,
        positionMs: Long = 420_000L
    ) = RewatchSession(
        ownerKey = "wife",
        canonicalSeriesId = "imdb:tt0386676",
        sessionId = "rewatch-session",
        episode = episode,
        positionMs = positionMs,
        durationMs = 1_200_000L,
        lastCompletedEpisode = RewatchEpisode(3, 4),
        phase = phase,
        updatedAtEpochMs = 11_000L
    )

    private fun resume(
        session: RewatchSession?,
        owner: String = "wife",
        id: String = "imdb:tt0386676",
        requested: RewatchEpisode = episode
    ) = resolveRewatchResumePosition(session, owner, id, requested)

    @Test
    fun replayProgressOverridesPreviouslyWatchedProviderPosition() {
        assertEquals(420_000L, resume(session(RewatchPhase.PLAYING)))
    }

    @Test
    fun queuedOrMetadataPendingReplayBeginsAtZero() {
        assertEquals(0L, resume(session(RewatchPhase.NEXT_UP)))
        assertEquals(0L, resume(session(RewatchPhase.AWAITING_METADATA)))
    }

    @Test
    fun finishedAndAbsentReplayReturnToNormalProgressLookup() {
        assertNull(resume(null))
        assertNull(resume(session(RewatchPhase.FINISHED)))
    }

    @Test
    fun differentOwnersShowsAndEpisodesDoNotOverrideResume() {
        val current = session(RewatchPhase.PLAYING)
        assertNull(resume(current, owner = "other-profile"))
        assertNull(resume(current, id = "imdb:some-other-show"))
        assertNull(resume(current, requested = RewatchEpisode(3, 6)))
    }

    @Test
    fun canonicalCaseAndWhitespaceMatchWithoutTitleGuessing() {
        assertEquals(
            420_000L,
            resume(session(RewatchPhase.PLAYING), id = " IMDB:TT0386676 ")
        )
    }

    @Test
    fun negativePositionNeverProducesNegativeSeek() {
        assertEquals(0L, resume(session(RewatchPhase.PLAYING, -100L)))
    }
}
