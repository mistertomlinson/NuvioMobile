package com.nuvio.app.features.rewatch

import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertNotNull

class RewatchManualEpisodeSelectionTest {
    private val office = "tt0386676"
    private val episode = RewatchEpisode(5, 19)

    @Test fun exactMatchingClickIsSingleUse() {
        RewatchManualEpisodeSelection.clearForTests()
        RewatchManualEpisodeSelection.arm(2, office, episode, 1_000L)
        assertNotNull(RewatchManualEpisodeSelection.consume(2, office, episode, 1_500L))
        assertNull(RewatchManualEpisodeSelection.consume(2, office, episode, 1_600L))
    }

    @Test fun wrongAccountOrEpisodeCannotClaimManualIntent() {
        RewatchManualEpisodeSelection.clearForTests()
        RewatchManualEpisodeSelection.arm(2, office, episode, 1_000L)
        assertNull(RewatchManualEpisodeSelection.consume(1, office, episode, 2_000L))
        assertNull(RewatchManualEpisodeSelection.consume(2, office, RewatchEpisode(5, 20), 2_000L))
        assertNull(RewatchManualEpisodeSelection.consume(2, "tt9999999", episode, 2_000L))
        assertNotNull(RewatchManualEpisodeSelection.consume(2, office, episode, 2_000L))
    }

    @Test fun resumeOffsetTravelsWithOneShotExplicitSelection() {
        RewatchManualEpisodeSelection.clearForTests()
        RewatchManualEpisodeSelection.arm(2, office, episode, 5_000L, 391_000L)
        val selected = assertNotNull(
            RewatchManualEpisodeSelection.consume(2, office, episode, 6_000L)
        )
        kotlin.test.assertEquals(391_000L, selected.resumePositionMs)
    }

    @Test fun expiredOrFutureClockIntentFailsClosed() {
        RewatchManualEpisodeSelection.clearForTests()
        RewatchManualEpisodeSelection.arm(2, office, episode, 1_000L)
        assertNull(RewatchManualEpisodeSelection.consume(2, office, episode, 122_001L))
        RewatchManualEpisodeSelection.arm(2, office, episode, 10_000L)
        assertNull(RewatchManualEpisodeSelection.consume(2, office, episode, 9_999L))
    }
}
