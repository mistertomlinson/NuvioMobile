package com.nuvio.app.features.player

import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.rewatch.RewatchEpisode
import com.nuvio.app.features.rewatch.RewatchPhase
import com.nuvio.app.features.rewatch.RewatchSession
import com.nuvio.app.features.rewatch.completeRewatchEpisode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayerRewatchCompletionCatalogTest {
    private fun video(season: Int, episode: Int, release: String = "2020-01-01") =
        MetaVideo(
            id = "episode:$season:$episode",
            title = "Episode $episode",
            released = release,
            season = season,
            episode = episode
        )

    private val all = listOf(
        video(1, 1), video(1, 2),
        video(2, 1), video(2, 2)
    )

    private fun meta(
        status: String? = "Ended",
        episodes: List<MetaVideo> = all
    ) = MetaDetails(
        id = "tt0386676",
        type = "series",
        name = "The Office",
        status = status,
        videos = episodes
    )

    private fun replay(selected: RewatchEpisode) = RewatchSession(
        ownerKey = "local-profile:2",
        canonicalSeriesId = "tt0386676",
        sessionId = "replay-session",
        episode = selected,
        positionMs = 240_000L,
        durationMs = 300_000L,
        lastCompletedEpisode = null,
        phase = RewatchPhase.PLAYING,
        updatedAtEpochMs = 1000L
    )

    @Test
    fun knownAiredSuccessorWorksAcrossSeasonsWithoutFinishing() {
        val current = RewatchEpisode(1, 2)
        val catalog = verifiedPlayerRewatchCompletionCatalog(
            meta(status = "Returning Series"), current
        )
        assertTrue(catalog.metadataComplete)
        assertEquals(listOf(current, RewatchEpisode(2, 1)), catalog.orderedEpisodes)
        val updated = completeRewatchEpisode(
            replay(current), current, catalog.orderedEpisodes,
            catalog.metadataComplete, 2000L
        )
        assertEquals(RewatchPhase.NEXT_UP, updated.phase)
        assertEquals(RewatchEpisode(2, 1), updated.episode)
    }

    @Test
    fun verifiedTerminalSeriesCanFinishAtActualFinalEpisode() {
        val finalEpisode = RewatchEpisode(2, 2)
        val catalog = verifiedPlayerRewatchCompletionCatalog(
            meta(), finalEpisode
        )
        assertTrue(catalog.metadataComplete)
        val updated = completeRewatchEpisode(
            replay(finalEpisode), finalEpisode, catalog.orderedEpisodes,
            catalog.metadataComplete, 2000L
        )
        assertEquals(RewatchPhase.FINISHED, updated.phase)
    }

    @Test
    fun returningSeriesWithoutNextEpisodeRemainsPending() {
        for (status in listOf(null, "Returning Series", "In Production", "")) {
            val result = verifiedPlayerRewatchCompletionCatalog(
                meta(status = status), RewatchEpisode(2, 2)
            )
            assertFalse(result.metadataComplete)
        }
    }

    @Test
    fun truncatedDuplicateOrAbsentCatalogCannotFinishReplay() {
        assertFalse(verifiedPlayerRewatchCompletionCatalog(
            null, RewatchEpisode(2, 2)
        ).metadataComplete)
        assertFalse(verifiedPlayerRewatchCompletionCatalog(
            meta(episodes = listOf(video(1, 1), video(1, 3))),
            RewatchEpisode(1, 3)
        ).metadataComplete)
        assertFalse(verifiedPlayerRewatchCompletionCatalog(
            meta(episodes = all + video(2, 2)),
            RewatchEpisode(2, 2)
        ).metadataComplete)
    }

    @Test
    fun futureEpisodeCannotBecomeNextUp() {
        assertFalse(verifiedPlayerRewatchCompletionCatalog(
            meta(
                status = "Returning Series",
                episodes = listOf(video(1, 1), video(1, 2, "2999-01-01"))
            ),
            RewatchEpisode(1, 1)
        ).metadataComplete)
    }
}
