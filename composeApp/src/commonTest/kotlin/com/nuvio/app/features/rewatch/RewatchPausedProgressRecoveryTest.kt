package com.nuvio.app.features.rewatch

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlin.test.*

class RewatchPausedProgressRecoveryTest {
    private class MemoryStorage : RewatchOutboxStorage {
        var raw: String? = null
        var fail = false
        override suspend fun read(profileId: Int) = raw
        override suspend fun write(profileId: Int, payload: String) {
            check(!fail)
            raw = payload
        }
        fun saved() = Json.decodeFromString<RewatchOutboxEnvelope>(requireNotNull(raw))
    }
    private val who = RewatchCommitIdentity(2, "12003", "simkl-device:tv")
    private val before = RewatchSession("local-profile:2", "tt0386676", "office",
        RewatchEpisode(6, 4), 30_000, 1_300_000, null, RewatchPhase.PLAYING, 100)
    private val paused = before.copy(positionMs = 31_948, updatedAtEpochMs = 200)
    private val remote = paused.copy(episode = RewatchEpisode(5, 17), updatedAtEpochMs = 0)
    private fun lookup(session: RewatchSession = remote, revision: Long = 1044) =
        RewatchRemoteLookup.Verified(RewatchRemoteCheckpoint.Synced(revision, session.sessionId), session)
    private fun queue(storage: MemoryStorage, current: () -> Boolean = { true }) =
        RewatchSyncOutbox(storage) { p, a, d -> current() &&
            p == who.profileId && a == who.simklAccountId && d == who.deviceId }
    private suspend fun pending(q: RewatchSyncOutbox) {
        assertTrue(q.enqueueCommittedTransition(2, "12003", who.deviceId, before, paused,
            RewatchRemoteCheckpoint.Synced(671, "office"), "paused-progress"))
    }
    private suspend fun archive(q: RewatchSyncOutbox, local: RewatchSession = paused,
        proof: RewatchRemoteLookup = lookup(), unchanged: suspend () -> Boolean = { true }) =
        q.archiveSupersededPlayback(who, local, 7, proof, unchanged)

    @Test fun exactQueuedEventsAreArchivedAndReceiptSurvivesRestart() = runBlocking {
        val storage = MemoryStorage(); val q = queue(storage); pending(q)
        val original = storage.saved().pending
        assertTrue(archive(q))
        assertEquals(original, storage.saved().recoveryArchive)
        assertEquals(emptyList<RewatchQueuedEvent>(), storage.saved().pending)
        val receipt = queue(storage).archivedProgressProof(who, before.canonicalSeriesId)!!
        assertTrue(receipt.cursor.matches(paused))
        assertEquals(1044L, receipt.cursor.revision)
        assertEquals(7L, receipt.generation)
    }

    @Test fun newerLocalProgressNotRepresentedInQueueCannotBeArchived() = runBlocking {
        val storage = MemoryStorage(); val q = queue(storage); pending(q)
        val original = storage.raw
        assertFalse(archive(q, local = paused.copy(positionMs = 40_000)))
        assertEquals(original, storage.raw)
    }

    @Test fun currentOrOlderRevisionCannotSupersedePendingProgress() = runBlocking {
        for (rev in listOf(1L, 671L)) {
            val storage = MemoryStorage(); val q = queue(storage); pending(q)
            val original = storage.raw
            assertFalse(archive(q, proof = lookup(revision = rev)))
            assertEquals(original, storage.raw)
        }
    }

    @Test fun sameEpisodeWithDifferentPositionAndChangedSessionStayPending() = runBlocking {
        for (session in listOf(paused.copy(positionMs = paused.positionMs + 1000, updatedAtEpochMs = 0), remote.copy(sessionId = "new-session"))) {
            val storage = MemoryStorage(); val q = queue(storage); pending(q)
            val original = storage.raw
            assertFalse(archive(q, proof = lookup(session)))
            assertEquals(original, storage.raw)
        }
    }

    @Test fun failedGetChangedProfileAndRacingSavePreserveEverything() = runBlocking {
        val storage = MemoryStorage(); var current = true; val q = queue(storage) { current }; pending(q)
        val original = storage.raw
        assertFalse(archive(q, proof = RewatchRemoteLookup.Invalid))
        assertFalse(archive(q, unchanged = { false }))
        var checks = 0
        assertFalse(archive(q, unchanged = { ++checks < 2 }))
        current = false
        assertFalse(archive(q))
        assertEquals(original, storage.raw)
    }

    @Test fun explicitManualSelectionNeverGetsAutomaticallyArchived() = runBlocking {
        val storage = MemoryStorage(); val q = queue(storage)
        val selected = paused.copy(episode = RewatchEpisode(6, 5), positionMs = 0,
            durationMs = 0, updatedAtEpochMs = 300)
        assertTrue(q.enqueueCommittedTransition(2, "12003", who.deviceId, paused, selected,
            RewatchRemoteCheckpoint.Synced(671, "office"), "explicit-picker", true))
        val original = storage.raw
        assertFalse(archive(q, local = selected))
        assertEquals(original, storage.raw)
    }

    @Test fun failedArchiveWriteLeavesOriginalQueueRetryable() = runBlocking {
        val storage = MemoryStorage(); val q = queue(storage); pending(q)
        val original = storage.raw; storage.fail = true
        try { archive(q); fail("Expected failed persistence") } catch (_: IllegalStateException) { }
        assertEquals(original, storage.raw)
    }

    @Test fun successfulVerifiedApplyRemovesReceiptButRetainsArchive() = runBlocking {
        val storage = MemoryStorage(); val q = queue(storage); pending(q)
        assertTrue(archive(q))
        val original = storage.saved().recoveryArchive
        assertTrue(q.recordVerifiedCleanBaseline(2, "12003", who.deviceId, remote, lookup()))
        assertNull(q.archivedProgressProof(who, before.canonicalSeriesId))
        assertEquals(original, storage.saved().recoveryArchive)
    }

    @Test fun archivedPausedProgressCanBeAppliedAfterRestartWithAtomicGenerationCheck() = runBlocking {
        val storage = MemoryStorage(); val q = queue(storage); pending(q)
        assertTrue(archive(q))
        val receipt = queue(storage).archivedProgressProof(who, before.canonicalSeriesId)
        fun apply(generation: Long = 7, proof: RewatchArchivedProgressProof? = receipt) =
            mergeRewatchRemoteWithLedger(listOf(paused), RewatchLocalSyncLedger(generation = generation),
                2, "12003", who.deviceId, paused, generation, paused.canonicalSeriesId,
                lookup(), false, 1000, archivedProgress = proof)
        assertEquals(remote.episode, apply()!!.first.single().episode)
        assertNull(apply(generation = 8))
        assertNull(apply(proof = receipt!!.copy(simklAccountId = "99999")))
        assertNull(apply(proof = receipt.copy(deviceId = "other-device")))
        assertNull(apply(proof = receipt.copy(cursor = receipt.cursor.copy(positionMs = 99_999))))
        // Once adopted, the old paused episode cannot write the cursor back.
        val adopted = apply()!!.first.single()
        assertEquals(adopted, recordRewatchProgress(adopted, paused.episode,
            paused.positionMs, paused.durationMs, 2000))
    }

    @Test fun identicalPausedSaveKeepsOriginalTimestampAndCursor() {
        assertSame(paused, recordRewatchProgress(paused, paused.episode,
            paused.positionMs, paused.durationMs, 1000))
    }

    @Test fun otherSeriesMutationDoesNotBlockFreshReadOfUnchangedSeries() {
        val baseline = lookup(paused.copy(updatedAtEpochMs = 0), 671)
        val ledger = certifyRewatchLocalClean(listOf(paused), RewatchLocalSyncLedger(generation = 3),
            2, "12003", who.deviceId, paused, 3, baseline, false)!!
        val other = paused.copy(canonicalSeriesId = "tt-other")
        val dirty = onLocalRewatchCommitted(ledger, other)
        fun apply(generation: Long) = mergeRewatchRemoteWithLedger(listOf(paused, other), dirty,
            2, "12003", who.deviceId, paused, generation, paused.canonicalSeriesId,
            lookup(), false, 1000)
        assertNull(apply(3))
        assertEquals(remote.episode, apply(4)!!.first.first().episode)
    }
}
