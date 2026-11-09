package com.nuvio.app.features.rewatch

import kotlin.test.assertEquals
import kotlin.test.Test

class RewatchActivationPolicyTest {
    private val episode = RewatchEpisode(3, 5)
    private val owner = "local-profile:2"
    private val series = "imdb:tt0386676"

    private fun active(phase: RewatchPhase, current: RewatchEpisode = episode) =
        RewatchSession(
            ownerKey = owner,
            canonicalSeriesId = series,
            sessionId = "rewatch-1",
            episode = current,
            positionMs = 12_000L,
            durationMs = 240_000L,
            lastCompletedEpisode = RewatchEpisode(3, 4),
            phase = phase,
            updatedAtEpochMs = 1_000L
        )

    private fun decide(
        sessions: List<RewatchSession> = emptyList(),
        alreadyWatched: Boolean = false,
        requested: RewatchEpisode = episode
    ) = decideRewatchActivation(
        storedSessions = sessions,
        ownerKey = owner,
        canonicalSeriesId = series,
        episode = requested,
        wasAlreadyWatched = alreadyWatched
    )

    @Test
    fun ordinaryFirstPlayNeverBeginsARewatch() {
        assertEquals(
            RewatchActivationDecision.NORMAL_PLAYBACK,
            decide(alreadyWatched = false)
        )
    }

    @Test
    fun previouslyWatchedEpisodeCreatesReplayFromCleanState() {
        assertEquals(
            RewatchActivationDecision.START_NEW,
            decide(alreadyWatched = true)
        )
    }

    @Test
    fun establishedReplayResumesWithoutProviderHistoryCheck() {
        for (phase in listOf(
            RewatchPhase.PLAYING,
            RewatchPhase.NEXT_UP,
            RewatchPhase.AWAITING_METADATA
        )) {
            assertEquals(
                RewatchActivationDecision.RESUME_EXISTING,
                decide(listOf(active(phase)), alreadyWatched = false)
            )
        }
    }

    @Test
    fun finishedReplayCanRestartOnAnotherWatchedEpisode() {
        assertEquals(
            RewatchActivationDecision.START_NEW,
            decide(
                sessions = listOf(active(RewatchPhase.FINISHED)),
                alreadyWatched = true,
                requested = RewatchEpisode(1, 1)
            )
        )
    }

    @Test
    fun unrelatedEpisodeCannotReplaceAnUnfinishedReplay() {
        assertEquals(
            RewatchActivationDecision.NORMAL_PLAYBACK,
            decide(
                sessions = listOf(active(RewatchPhase.NEXT_UP)),
                alreadyWatched = true,
                requested = RewatchEpisode(3, 6)
            )
        )
    }

    @Test
    fun otherUserAndSeriesDoNotSuppressIndependentReplay() {
        val otherOwner = active(RewatchPhase.PLAYING).copy(ownerKey = "local-profile:1")
        val otherSeries = active(RewatchPhase.PLAYING).copy(canonicalSeriesId = "imdb:other")
        assertEquals(
            RewatchActivationDecision.START_NEW,
            decide(listOf(otherOwner), alreadyWatched = true)
        )
        assertEquals(
            RewatchActivationDecision.START_NEW,
            decide(listOf(otherSeries), alreadyWatched = true)
        )
    }
}
