package com.nuvio.app.features.rewatch

import kotlin.test.Test
import kotlin.test.assertEquals

class RewatchPlaybackWritePolicyTest {
    private val episode = RewatchEpisode(3, 5)

    private fun session(phase: RewatchPhase = RewatchPhase.PLAYING) =
        RewatchSession(
            ownerKey = "wife",
            canonicalSeriesId = "imdb:tt0386676",
            sessionId = "replay-1",
            episode = episode,
            positionMs = 123_000L,
            durationMs = 1_200_000L,
            lastCompletedEpisode = RewatchEpisode(3, 4),
            phase = phase,
            updatedAtEpochMs = 1_000L
        )

    private fun route(
        candidate: RewatchSession?,
        owner: String = "wife",
        id: String = "imdb:tt0386676",
        requestedEpisode: RewatchEpisode = episode
    ) = resolveRewatchPlaybackWriteRoute(
        candidate, owner, id, requestedEpisode
    )

    @Test
    fun allEstablishedReplayPhasesUseReplayOnlyWrites() {
        for (phase in listOf(
            RewatchPhase.PLAYING,
            RewatchPhase.NEXT_UP,
            RewatchPhase.AWAITING_METADATA
        )) {
            assertEquals(
                RewatchPlaybackWriteRoute.REWATCH_SESSION_ONLY,
                route(session(phase))
            )
        }
    }

    @Test
    fun noReplaySessionUsesOrdinaryProviderTracking() {
        assertEquals(
            RewatchPlaybackWriteRoute.NORMAL_TRACKING,
            route(null)
        )
    }

    @Test
    fun finishedReplayUsesOrdinaryProviderTracking() {
        assertEquals(
            RewatchPlaybackWriteRoute.NORMAL_TRACKING,
            route(session(RewatchPhase.FINISHED))
        )
    }

    @Test
    fun anotherProfileCannotSuppressOriginalHistory() {
        assertEquals(
            RewatchPlaybackWriteRoute.NORMAL_TRACKING,
            route(session(), owner = "husband")
        )
    }

    @Test
    fun unrelatedSeriesAndEpisodesUseNormalTracking() {
        assertEquals(
            RewatchPlaybackWriteRoute.NORMAL_TRACKING,
            route(session(), id = "imdb:other")
        )
        assertEquals(
            RewatchPlaybackWriteRoute.NORMAL_TRACKING,
            route(session(), requestedEpisode = RewatchEpisode(3, 6))
        )
    }

    @Test
    fun contentIdentityIsCanonicalizedOnlyByCaseAndWhitespace() {
        assertEquals(
            RewatchPlaybackWriteRoute.REWATCH_SESSION_ONLY,
            route(session(), id = " IMDB:TT0386676 ")
        )
        assertEquals(
            RewatchPlaybackWriteRoute.NORMAL_TRACKING,
            route(session(), id = "tmdb:unresolved-alias")
        )
    }
}
