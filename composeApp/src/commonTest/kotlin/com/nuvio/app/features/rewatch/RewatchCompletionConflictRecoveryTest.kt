package com.nuvio.app.features.rewatch

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlin.test.*

/** Exact reported state: COMPLETE_EPISODE@1040, local S6E5, VPS S5E17@1044. */
class RewatchCompletionConflictRecoveryTest {
    private class Disk : RewatchOutboxStorage {
        var raw: String? = null
        override suspend fun read(profileId: Int) = raw
        override suspend fun write(profileId: Int, payload: String) { raw = payload }
        fun saved() = Json.decodeFromString<RewatchOutboxEnvelope>(requireNotNull(raw))
    }
    private val identity = RewatchCommitIdentity(2, "12003", "simkl-device:tv")
    private val playing = RewatchSession("local-profile:2", "tt0386676", "office-session",
        RewatchEpisode(6, 4), 1_200_000, 1_298_966, null, RewatchPhase.PLAYING, 100)
    private val completed = playing.copy(episode = RewatchEpisode(6, 5), positionMs = 0,
        durationMs = 0, phase = RewatchPhase.NEXT_UP,
        lastCompletedEpisode = playing.episode, updatedAtEpochMs = 200)
    private val remote = playing.copy(episode = RewatchEpisode(5, 17), positionMs = 31_948,
        updatedAtEpochMs = 0)
    private fun lookup(session: RewatchSession = remote, revision: Long = 1044) =
        RewatchRemoteLookup.Verified(RewatchRemoteCheckpoint.Synced(revision, session.sessionId), session)
    private fun queue(disk: Disk) = RewatchSyncOutbox(disk) { p, a, d ->
        p == identity.profileId && a == identity.simklAccountId && d == identity.deviceId
    }
    private suspend fun append(q: RewatchSyncOutbox, before: RewatchSession,
        after: RewatchSession, event: String) {
        assertTrue(q.enqueueCommittedTransition(2, "12003", identity.deviceId,
            before, after, RewatchRemoteCheckpoint.Synced(1040, playing.sessionId), event))
    }
    private suspend fun archive(q: RewatchSyncOutbox, local: RewatchSession = completed,
        proof: RewatchRemoteLookup = lookup()) =
        q.archiveSupersededPlayback(identity, local, 1045, proof, { true })

    @Test fun reportedCompletionIsArchivedVerbatimAndNextUpReceiptSurvivesRestart() = runBlocking {
        val disk = Disk(); val q = queue(disk)
        assertTrue(q.recordVerifiedCleanBaseline(2, "12003", identity.deviceId,
            playing, lookup(playing.copy(updatedAtEpochMs = 0), 1040)))
        append(q, playing, completed, "completion-1040")
        val original = disk.saved().pending
        assertEquals(1040L, original.single().expectedRevision)
        assertEquals(RewatchOutboxDrainResult.CONFLICT,
            q.drain(2, "12003", identity.deviceId) { RewatchOutboxSubmitResult.Conflict })
        assertTrue(archive(q))
        assertEquals(original, disk.saved().recoveryArchive)
        assertTrue(disk.saved().pending.isEmpty())
        val receipt = queue(disk).archivedProgressProof(identity, playing.canonicalSeriesId)!!
        assertTrue(receipt.cursor.matches(completed))
        assertEquals("NEXT_UP", receipt.cursor.phase)
        assertEquals(1045L, receipt.generation)
        assertEquals(1044L, receipt.cursor.revision)
    }

    @Test fun progressCompletionAndStartedSuccessorAreReplayedAsOneContiguousChain() = runBlocking {
        val disk = Disk(); val q = queue(disk)
        val advanced = playing.copy(positionMs = 1_250_000, updatedAtEpochMs = 150)
        val started = completed.copy(positionMs = 5000, durationMs = playing.durationMs,
            phase = RewatchPhase.PLAYING, updatedAtEpochMs = 300)
        append(q, playing, advanced, "progress-1040")
        append(q, advanced, completed, "completion-1041")
        append(q, completed, started, "progress-1042")
        val original = disk.saved().pending
        assertTrue(archive(q, started))
        assertEquals(original, disk.saved().recoveryArchive)
        assertTrue(queue(disk).archivedProgressProof(identity, playing.canonicalSeriesId)!!.cursor.matches(started))
    }

    @Test fun completionThenMetadataResolutionCanBeSupersededTogether() = runBlocking {
        val disk = Disk(); val q = queue(disk)
        val waiting = completed.copy(episode = playing.episode, phase = RewatchPhase.AWAITING_METADATA)
        append(q, playing, waiting, "completion-awaiting")
        append(q, waiting, completed, "metadata-next-up")
        val original = disk.saved().pending
        assertTrue(archive(q))
        assertEquals(original, disk.saved().recoveryArchive)
    }

    @Test fun standaloneMetadataResolutionCanExplainCurrentNextUp() = runBlocking {
        val disk = Disk(); val q = queue(disk)
        val waiting = completed.copy(episode = playing.episode, phase = RewatchPhase.AWAITING_METADATA)
        append(q, waiting, completed, "metadata-only")
        assertTrue(archive(q))
    }

    @Test fun terminalCompletionCanBeArchivedWithoutDroppingItsReceipt() = runBlocking {
        val disk = Disk(); val q = queue(disk)
        val finished = completed.copy(episode = playing.episode, phase = RewatchPhase.FINISHED)
        append(q, playing, finished, "terminal-completion")
        assertTrue(archive(q, finished))
        assertEquals("FINISHED", queue(disk).archivedProgressProof(identity, playing.canonicalSeriesId)!!.cursor.phase)
    }

    @Test fun awaitingMetadataCompletionCanBeArchived() = runBlocking {
        val disk = Disk(); val q = queue(disk)
        val waiting = completed.copy(episode = playing.episode, phase = RewatchPhase.AWAITING_METADATA)
        append(q, playing, waiting, "completion-awaiting")
        assertTrue(archive(q, waiting))
        assertEquals("AWAITING_METADATA", queue(disk).archivedProgressProof(identity, playing.canonicalSeriesId)!!.cursor.phase)
    }

    @Test fun localCursorNotExplainedByCompletionKeepsOriginalQueue() = runBlocking {
        for (changed in listOf(completed.copy(positionMs = 1),
            completed.copy(lastCompletedEpisode = RewatchEpisode(6, 3)),
            completed.copy(episode = RewatchEpisode(6, 6)),
            completed.copy(phase = RewatchPhase.PLAYING))) {
            val disk = Disk(); val q = queue(disk)
            append(q, playing, completed, "completion")
            val original = disk.raw
            assertFalse(archive(q, changed))
            assertEquals(original, disk.raw)
        }
    }

    @Test fun duplicateCompletionWithoutStartingNextUpFailsClosed() = runBlocking {
        val disk = Disk(); val q = queue(disk)
        append(q, playing, completed, "completion-1040")
        val invalid = requireNotNull(planCommittedRewatchEvent(
            completed.copy(phase = RewatchPhase.PLAYING),
            completed.copy(episode = RewatchEpisode(6, 6), lastCompletedEpisode = completed.episode),
            RewatchRemoteCheckpoint.Synced(1041, playing.sessionId), "unstarted-completion", identity.deviceId))
        assertTrue(q.enqueue(2, "12003", identity.deviceId, invalid))
        val original = disk.raw
        assertFalse(archive(q, completed.copy(episode = RewatchEpisode(6, 6), lastCompletedEpisode = completed.episode)))
        assertEquals(original, disk.raw)
    }

    @Test fun nonContiguousRevisionAndMalformedSuccessorNeverGetArchived() = runBlocking {
        val event = requireNotNull(planCommittedRewatchEvent(playing, completed,
            RewatchRemoteCheckpoint.Synced(1040, playing.sessionId), "completion", identity.deviceId))
        for (broken in listOf(event.copy(payload = event.payload.replace("\"episode\":5", "\"episode\":3")),
            event.copy(payload = event.payload.replace("\"expectedRevision\":1040", "\"expectedRevision\":1039")))) {
            val disk = Disk(); val q = queue(disk)
            assertTrue(q.enqueue(2, "12003", identity.deviceId, broken))
            val original = disk.raw
            assertFalse(archive(q))
            assertEquals(original, disk.raw)
        }
    }

    @Test fun currentRevisionAndDifferentSessionCannotSupersedeCompletion() = runBlocking {
        for (proof in listOf(lookup(revision = 1040), lookup(remote.copy(sessionId = "another-session")))) {
            val disk = Disk(); val q = queue(disk)
            append(q, playing, completed, "completion")
            val original = disk.raw
            assertFalse(archive(q, proof = proof))
            assertEquals(original, disk.raw)
        }
    }

    @Test fun queuedExplicitStopIsPreserved() = runBlocking {
        val disk = Disk(); val q = queue(disk)
        val stopped = playing.copy(phase = RewatchPhase.FINISHED, updatedAtEpochMs = 200)
        append(q, playing, stopped, "explicit-stop")
        val original = disk.raw
        assertFalse(archive(q, stopped))
        assertEquals(original, disk.raw)
    }

    @Test fun automaticCursorAlreadyPresentOnServerCanCertifyWithoutAnotherPost() = runBlocking {
        for (local in listOf(completed, playing.copy(positionMs = 1_250_000, updatedAtEpochMs = 200))) {
            val disk = Disk(); val q = queue(disk)
            append(q, playing, local, "automatic-playback")
            val proof = lookup(local.copy(updatedAtEpochMs = 0))
            assertTrue(archive(q, local, proof))
            assertTrue(q.recordVerifiedCleanBaseline(2, "12003", identity.deviceId, local, proof))
            assertNull(q.archivedProgressProof(identity, playing.canonicalSeriesId))
            assertEquals(1, disk.saved().recoveryArchive.size)
        }
    }

    @Test fun archivedNextUpAdoptsExistingServerCursorWithAtomicGenerationGuard() = runBlocking {
        val disk = Disk(); val q = queue(disk)
        append(q, playing, completed, "completion")
        assertTrue(archive(q))
        val proof = queue(disk).archivedProgressProof(identity, playing.canonicalSeriesId)
        fun apply(generation: Long) = mergeRewatchRemoteWithLedger(listOf(completed),
            RewatchLocalSyncLedger(generation = generation), 2, "12003", identity.deviceId,
            completed, generation, playing.canonicalSeriesId, lookup(), false, 300,
            archivedProgress = proof)
        val adopted = apply(1045)!!.first.single()
        assertEquals(RewatchEpisode(5, 17), adopted.episode)
        assertEquals(31_948L, adopted.positionMs)
        assertNull(apply(1046))
        assertEquals(adopted, recordRewatchProgress(adopted, playing.episode,
            playing.positionMs, playing.durationMs, 400))
    }
}
