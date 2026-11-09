package com.nuvio.app.features.home

import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.rewatch.RewatchEpisode
import com.nuvio.app.features.rewatch.RewatchPhase
import com.nuvio.app.features.rewatch.RewatchSession
import com.nuvio.app.features.watchprogress.ContinueWatchingItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HomeRewatchProjectionTest {
    private fun replay(
        phase: RewatchPhase = RewatchPhase.NEXT_UP,
        episode: RewatchEpisode = RewatchEpisode(3, 6),
        position: Long = 0L
    ) = RewatchSession(
        ownerKey = "local-profile:2",
        canonicalSeriesId = "tt0386676",
        sessionId = "replay-1",
        episode = episode,
        positionMs = position,
        durationMs = 600_000L,
        lastCompletedEpisode = RewatchEpisode(3, 5),
        phase = phase,
        updatedAtEpochMs = 1000L
    )

    private fun meta(
        videos: List<MetaVideo> = listOf(
            MetaVideo(
                id = "tt0386676:3:6", title = "Episode 6",
                season = 3, episode = 6, released = "2007-01-01"
            )
        )
    ) = MetaDetails(
        id = "tt0386676", type = "series",
        name = "The Office", poster = "poster",
        background = "background", videos = videos
    )

    private fun normal(
        id: String = "tt0386676",
        season: Int = 9,
        episode: Int = 23
    ) = ContinueWatchingItem(
        parentMetaId = id, parentMetaType = "series",
        videoId = "$id:$season:$episode", title = "Old Provider CW",
        subtitle = "S$season E$episode", imageUrl = null,
        seasonNumber = season, episodeNumber = episode,
        isNextUp = true, resumePositionMs = 0L,
        durationMs = 0L, progressFraction = 0f
    )

    @Test
    fun replayNextUpUsesExactEpisodeAndNeverClaimsNewRelease() {
        val card = requireNotNull(projectHomeRewatchCard(replay(), meta()))
        assertTrue(card.isRewatch)
        assertTrue(card.isNextUp)
        assertEquals(3, card.seasonNumber)
        assertEquals(6, card.episodeNumber)
        assertEquals("tt0386676:3:6", card.videoId)
        assertEquals(5, card.nextUpSeedEpisodeNumber)
        assertFalse(card.isReleaseAlert)
        assertFalse(card.isNewSeasonRelease)
    }

    @Test
    fun inProgressReplayRestoresItsOwnCursor() {
        val current = replay(RewatchPhase.PLAYING, position = 270_000L)
        val card = requireNotNull(projectHomeRewatchCard(current, meta()))
        assertEquals(270_000L, card.resumePositionMs)
        assertEquals(600_000L, card.durationMs)
        assertEquals(0.45f, card.progressFraction)
        assertFalse(card.isNextUp)
    }

    @Test
    fun unknownMetadataDoesNotFabricatePlayableEpisode() {
        assertNull(projectHomeRewatchCard(replay(), meta(emptyList())))
        assertNull(projectHomeRewatchCard(replay(), meta(
            listOf(MetaVideo(id = "", title = "Episode", season = 3, episode = 6))
        )))
        assertNull(projectHomeRewatchCard(replay(), meta(
            listOf(
                MetaVideo(id = "one", title = "One", season = 3, episode = 6),
                MetaVideo(id = "two", title = "Two", season = 3, episode = 6)
            )
        )))
    }

    @Test
    fun pendingAndFinishedReplaysNeverProjectCards() {
        assertNull(projectHomeRewatchCard(
            replay(RewatchPhase.AWAITING_METADATA), meta()
        ))
        assertNull(projectHomeRewatchCard(
            replay(RewatchPhase.FINISHED), meta()
        ))
    }

    @Test
    fun verifiedReplayReplacesOldSimklNextUpButPreservesUnrelatedItems() {
        val movie = normal(id = "other-movie")
        val old = normal()
        val current = replay()
        val replayCard = requireNotNull(projectHomeRewatchCard(current, meta()))
        val merged = mergeHomeRewatchCards(
            listOf(movie, old), listOf(current), listOf(replayCard)
        )
        assertEquals(2, merged.size)
        assertEquals(movie, merged[0])
        assertEquals(replayCard, merged[1])
    }

    @Test
    fun failedMetadataSuppressesStaleProviderCardInsteadOfShowingWrongEpisode() {
        val normal = normal()
        assertTrue(mergeHomeRewatchCards(
            listOf(normal), listOf(replay()), emptyList()
        ).isEmpty())
    }

    @Test
    fun finishTombstonePreventsOldProviderCardFromResurrecting() {
        assertTrue(mergeHomeRewatchCards(
            listOf(normal()), listOf(replay(RewatchPhase.FINISHED)), emptyList()
        ).isEmpty())
    }

    @Test
    fun latePreviousEpisodeResultCannotReinsertOldCardAfterAdvance() {
        val old = replay()
        val oldCard = requireNotNull(projectHomeRewatchCard(old, meta()))
        val advanced = replay(
            phase = RewatchPhase.NEXT_UP,
            episode = RewatchEpisode(3, 7)
        )
        val merged = mergeHomeRewatchCards(
            listOf(normal()), listOf(advanced), listOf(oldCard)
        )
        assertTrue(merged.isEmpty())
    }

    @Test
    fun lateProgressCardCannotOverrideQueuedNextUpPhase() {
        val old = replay(RewatchPhase.PLAYING, position = 40_000L)
        val oldCard = requireNotNull(projectHomeRewatchCard(old, meta()))
        val next = replay(RewatchPhase.NEXT_UP)
        assertTrue(mergeHomeRewatchCards(
            listOf(normal()), listOf(next), listOf(oldCard)
        ).isEmpty())
    }

    @Test
    fun savedReplayPositionAlwaysSupersedesStaleRenderedPercentage() {
        val prior = replay(RewatchPhase.PLAYING, position = 30_000L)
        val priorCard = requireNotNull(projectHomeRewatchCard(prior, meta()))
        val advanced = replay(RewatchPhase.PLAYING, position = 240_000L)
        val actual = mergeHomeRewatchCards(
            emptyList(), listOf(advanced), listOf(priorCard)
        ).single()
        assertEquals(240_000L, actual.resumePositionMs)
        assertEquals(0.4f, actual.progressFraction)
    }
}
