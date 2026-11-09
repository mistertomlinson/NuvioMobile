package com.nuvio.app.features.player

import com.nuvio.app.features.rewatch.RewatchEpisode
import com.nuvio.app.features.rewatch.RewatchPhase
import com.nuvio.app.features.rewatch.RewatchSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayerRewatchWriteGateTest {
    private val current = PlaybackKey("stream:one", "episode:5", 3, 5)
    private val successor = PlaybackKey("stream:two", "episode:6", 3, 6)

    private fun session(
        episode: RewatchEpisode = RewatchEpisode(3, 5),
        phase: RewatchPhase = RewatchPhase.PLAYING
    ) = RewatchSession(
        ownerKey = "local-profile:2",
        canonicalSeriesId = "tt0386676",
        sessionId = "replay-session",
        episode = episode,
        positionMs = 120_000L,
        durationMs = 240_000L,
        lastCompletedEpisode = RewatchEpisode(3, 4),
        phase = phase,
        updatedAtEpochMs = 2000L
    )

    @Test
    fun unclassifiedEpisodeNeverWritesNormalProviderHistory() {
        assertEquals(PlayerRewatchWriteMode.PENDING,
            playerRewatchWriteMode(current, null, 2, null, null))
        assertEquals(PlayerRewatchWriteMode.PENDING,
            playerRewatchWriteMode(current, successor, 2, 2, null))
        assertEquals(PlayerRewatchWriteMode.PENDING,
            playerRewatchWriteMode(current, current, 2, 1, null))
    }

    @Test
    fun ordinaryVideoOnlyWritesAfterExplicitClassification() {
        assertEquals(PlayerRewatchWriteMode.NORMAL,
            playerRewatchWriteMode(current, current, 2, 2, null))
    }

    @Test
    fun replayNeverDowngradesToNormalAfterItsCursorAdvancesOrFinishes() {
        for (phase in listOf(
            RewatchPhase.PLAYING,
            RewatchPhase.NEXT_UP,
            RewatchPhase.AWAITING_METADATA,
            RewatchPhase.FINISHED
        )) {
            assertEquals(PlayerRewatchWriteMode.REPLAY,
                playerRewatchWriteMode(
                    current, current, 2, 2,
                    session(episode = RewatchEpisode(3, 6), phase = phase)
                ))
        }
    }

    @Test
    fun newAutoplayEpisodeBecomesPendingUntilItIsClassified() {
        assertEquals(PlayerRewatchWriteMode.PENDING,
            playerRewatchWriteMode(successor, current, 2, 2, session()))
    }

    @Test
    fun completedReplayHandoffRequiresExactSessionAndEpisode() {
        val handoff = PlayerRewatchAutoplayHandoff(
            "replay-session", RewatchEpisode(3, 6)
        )
        assertTrue(playerRewatchAutoplayHandoffReady(
            handoff,
            session(
                episode = RewatchEpisode(3, 6),
                phase = RewatchPhase.NEXT_UP
            ),
            RewatchEpisode(3, 6)
        ))
        assertFalse(playerRewatchAutoplayHandoffReady(
            handoff, session(), RewatchEpisode(3, 6)
        ))
        assertFalse(playerRewatchAutoplayHandoffReady(
            handoff, null, RewatchEpisode(3, 6)
        ))
        assertFalse(playerRewatchAutoplayHandoffReady(
            handoff,
            session(
                episode = RewatchEpisode(3, 6),
                phase = RewatchPhase.FINISHED
            ),
            RewatchEpisode(3, 6)
        ))
    }

    @Test
    fun invalidSeasonOrEpisodeDoesNotCreateReplayIdentity() {
        assertEquals(null, playerRewatchEpisode(null, 5))
        assertEquals(null, playerRewatchEpisode(0, 1))
        assertEquals(null, playerRewatchEpisode(1, 0))
        assertEquals(RewatchEpisode(3, 5), playerRewatchEpisode(3, 5))
    }
}
