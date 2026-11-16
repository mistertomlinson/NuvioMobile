package com.nuvio.app.features.details

import com.nuvio.app.features.rewatch.ProfileRewatchSnapshot
import com.nuvio.app.features.rewatch.RewatchEpisode
import com.nuvio.app.features.rewatch.RewatchManualEpisodeSelection
import com.nuvio.app.features.rewatch.RewatchPhase
import com.nuvio.app.features.rewatch.RewatchSession
import kotlin.test.*

class RewatchDetailsActionTest {
    private val replay = RewatchSession("local-profile:2", "tt0386676", "office",
        RewatchEpisode(5, 17), 69_348, 1_853_280, null, RewatchPhase.PLAYING, 100)
    private fun video(episode: Int, available: Boolean = true) = MetaVideo(
        id = "actual-addon-episode-$episode", title = "Episode $episode", season = 5,
        episode = episode, released = "2009-01-01", available = available)
    private fun meta(videos: List<MetaVideo> = listOf(video(16), video(17), video(18), video(22))) =
        MetaDetails("tt0386676", "series", "The Office", videos = videos)
    private fun snapshot(session: RewatchSession = replay, profile: Int = 2) =
        ProfileRewatchSnapshot(profile, listOf(session))
    private val history22 = SeriesPrimaryAction("Resume S5 E22", "old-provider-22", 5, 22,
        "Old history", null, 120_000)
    private fun resolve(session: RewatchSession = replay, metadata: MetaDetails = meta()) =
        metadata.rewatchDetailsAction(2, snapshot(session), "2026-11-16")

    @Test fun syncedCursorOverridesS5E22LabelTargetAndPositionTogether() {
        var fallbackCalled = false
        val action = selectDetailsPrimaryAction(resolve()) { fallbackCalled = true; history22 }
        assertFalse(fallbackCalled)
        assertEquals("Resume S5 E17", action?.label)
        assertEquals("actual-addon-episode-17", action?.videoId)
        assertEquals(5, action?.seasonNumber)
        assertEquals(17, action?.episodeNumber)
        assertEquals(69_348L, action?.resumePositionMs)
    }

    @Test fun changedCursorRecalculatesLabelAndTargetWithoutProviderHistoryChange() {
        val earlier = replay.copy(episode = RewatchEpisode(5, 16), positionMs = 0, durationMs = 0)
        val action = selectDetailsPrimaryAction(resolve(earlier)) { history22 }
        assertEquals("Play S5 E16", action?.label)
        assertEquals("actual-addon-episode-16", action?.videoId)
        assertEquals(0L, action?.resumePositionMs)
    }

    @Test fun nextUpCarriesExplicitZeroInsteadOfOldProviderResumePosition() {
        val next = replay.copy(episode = RewatchEpisode(5, 18), phase = RewatchPhase.NEXT_UP,
            positionMs = 0, durationMs = 0, lastCompletedEpisode = replay.episode)
        val action = resolve(next)?.action
        assertEquals("Next Up • S5 E18", action?.label)
        assertEquals("actual-addon-episode-18", action?.videoId)
        assertEquals(0L, action?.resumePositionMs)
    }

    @Test fun correctProfileOwnerAndShowAreRequired() {
        assertNull(meta().rewatchDetailsAction(3, snapshot(), "2026-11-16"))
        assertNull(meta().rewatchDetailsAction(2, snapshot(profile = 3), "2026-11-16"))
        assertNull(resolve(replay.copy(ownerKey = "local-profile:3")))
        assertNull(resolve(replay.copy(canonicalSeriesId = "tt9999999")))
        assertNull(meta().rewatchDetailsAction(2, null, "2026-11-16"))
    }

    @Test fun imdbAliasesUseTheActualMetadataVideoWithoutChangingCursorIdentity() {
        val metadata = meta().copy(id = "tmdb:2316", imdbId = "imdb:tt0386676")
        val action = resolve(metadata = metadata)
        assertEquals("actual-addon-episode-17", action?.action?.videoId)
        assertEquals("tt0386676", action?.session?.canonicalSeriesId)
        assertEquals(17, resolve(replay.copy(canonicalSeriesId = "imdb:tt0386676"))?.action?.episodeNumber)
    }

    @Test fun missingOrAmbiguousMetadataBlocksOldHistoryFallback() {
        for (videos in listOf(listOf(video(22)), listOf(video(17), video(17)),
            listOf(video(17, available = false)), listOf(video(17).copy(id = "")))) {
            val override = resolve(metadata = meta(videos))
            assertNotNull(override)
            assertTrue(override.playbackBlocked)
            assertEquals("Resume S5 E17", override.label)
            assertNull(selectDetailsPrimaryAction(override) { fail("Old S5E22 must stay masked") })
        }
    }

    @Test fun unavailableFutureEpisodeIsNotTurnedIntoAnOldHistoryTarget() {
        val override = resolve(metadata = meta(listOf(video(17).copy(released = "2099-01-01"))))
        assertTrue(override?.playbackBlocked == true)
    }

    @Test fun noActiveRewatchPreservesExistingPrimaryAction() {
        for (phase in listOf(RewatchPhase.FINISHED, RewatchPhase.AWAITING_METADATA)) {
            assertSame(history22, selectDetailsPrimaryAction(resolve(replay.copy(phase = phase))) { history22 })
        }
        assertSame(history22, selectDetailsPrimaryAction(null) { history22 })
        assertNull(resolve(metadata = meta().copy(type = "movie")))
    }

    @Test fun stalePrimaryActionCannotConsumeAnAbandonedEpisodePickerIntent() {
        RewatchManualEpisodeSelection.clearForTests()
        RewatchManualEpisodeSelection.arm(2, replay.canonicalSeriesId, RewatchEpisode(5, 22), 100)
        RewatchManualEpisodeSelection.clearForPrimaryAction()
        assertNull(RewatchManualEpisodeSelection.consume(2, replay.canonicalSeriesId, RewatchEpisode(5, 22), 101))
        // An actual episode-list click continues to authorize its chosen episode.
        RewatchManualEpisodeSelection.arm(2, replay.canonicalSeriesId, RewatchEpisode(5, 16), 102)
        assertNotNull(RewatchManualEpisodeSelection.consume(2, replay.canonicalSeriesId, RewatchEpisode(5, 16), 103))
    }
}
