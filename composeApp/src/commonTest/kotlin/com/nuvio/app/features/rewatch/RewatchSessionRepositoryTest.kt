package com.nuvio.app.features.rewatch

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class RewatchSessionRepositoryTest {
    private fun session(
        owner: String = "owner-A",
        series: String = "imdb:tt0386676",
        id: String = "rewatch-1",
        episode: Int = 5,
        updated: Long = 1000L
    ) = RewatchSession(
        ownerKey = owner,
        canonicalSeriesId = series,
        sessionId = id,
        episode = RewatchEpisode(3, episode),
        positionMs = 77_000L,
        durationMs = 1_200_000L,
        lastCompletedEpisode = RewatchEpisode(3, episode - 1),
        phase = RewatchPhase.PLAYING,
        updatedAtEpochMs = updated
    )

    @Test
    fun emptyStoreIsIndependentOfExistingHistory() {
        assertTrue(decodeRewatchSessionPayload(null).isEmpty())
    }

    @Test
    fun jsonRoundTripPreservesReplayCursorAndProfileOwner() {
        val entries = listOf(session(), session(series = "tmdb:other", episode = 2))
        val json = Json.encodeToString(RewatchSessionsEnvelope(sessions = entries))
        assertEquals(entries, decodeRewatchSessionPayload(json))
    }

    @Test
    fun unknownVersionCannotSilentlyReplaceStoredSessions() {
        assertFailsWith<IllegalArgumentException> {
            decodeRewatchSessionPayload("""{"schemaVersion":2,"sessions":[]}""")
        }
    }

    @Test
    fun malformedStorageFailsClosed() {
        assertFailsWith<Exception> {
            decodeRewatchSessionPayload("{corrupt")
        }
        assertFailsWith<IllegalArgumentException> {
            val duplicate = listOf(session(), session(series = " IMDB:TT0386676 "))
            decodeRewatchSessionPayload(
                Json.encodeToString(RewatchSessionsEnvelope(sessions = duplicate))
            )
        }
    }

    @Test
    fun sameSeriesReplayAdvancesRegardlessOfIdentityCase() {
        val previous = session()
        val next = session(series = "  IMDB:TT0386676 ", episode = 6, updated = 2000L)
        val changed = mergeRewatchSessionUpdate(listOf(previous), next)
        assertEquals(1, changed?.size)
        assertEquals(6, changed?.single()?.episode?.episode)
    }

    @Test
    fun staleEventsAndNewSessionIdsNeedExplicitIntent() {
        val current = session(episode = 6, updated = 2000L)
        assertNull(
            mergeRewatchSessionUpdate(
                listOf(current), session(episode = 5, updated = 1000L)
            )
        )
        val restarted = session(id = "rewatch-2", updated = 3000L)
        assertNull(mergeRewatchSessionUpdate(listOf(current), restarted))
        assertEquals(
            restarted,
            mergeRewatchSessionUpdate(listOf(current), restarted, true)?.single()
        )
    }

    @Test
    fun separateOwnersDoNotReplaceEachOthersSessions() {
        val current = session(owner = "wife")
        assertNull(
            mergeRewatchSessionUpdate(
                listOf(current), session(owner = "other", updated = 3000L), true
            )
        )
    }
}
