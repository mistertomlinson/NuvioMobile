package com.nuvio.app.features.rewatch

import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.Test

class RewatchLocalSyncLedgerTest {
    private val account = "12003"
    private val device = "simkl-device:android-one"
    private val original = requireNotNull(startRewatchSession(
        ownerKey = "local-profile:2",
        canonicalSeriesId = "imdb:tt0386676",
        sessionId = "office-session",
        episode = RewatchEpisode(3, 18),
        wasAlreadyWatched = true,
        updatedAtEpochMs = 100
    )).copy(positionMs = 50_000, durationMs = 1_500_000)
    private val nextUp = original.copy(
        episode = RewatchEpisode(3, 19),
        positionMs = 0,
        durationMs = 0,
        lastCompletedEpisode = RewatchEpisode(3, 18),
        phase = RewatchPhase.NEXT_UP,
        updatedAtEpochMs = 0
    )

    private fun verified(
        session: RewatchSession = original,
        revision: Long = 5
    ) = RewatchRemoteLookup.Verified(
        RewatchRemoteCheckpoint.Synced(revision, session.sessionId),
        session.copy(updatedAtEpochMs = 0)
    )

    private fun clean(
        local: RewatchSession = original,
        remote: RewatchRemoteLookup = verified(),
        generation: Long = 3
    ): RewatchLocalSyncLedger {
        val previous = RewatchLocalSyncLedger(generation = generation)
        return requireNotNull(certifyRewatchLocalClean(
            listOf(local), previous, 2, account, device,
            local, generation, remote, false
        ))
    }

    private fun apply(
        sessions: List<RewatchSession> = listOf(original),
        ledger: RewatchLocalSyncLedger = clean(),
        local: RewatchSession? = original,
        generation: Long = ledger.generation,
        remote: RewatchRemoteLookup = verified(nextUp, 6),
        simklAccount: String = account,
        pending: Boolean = false
    ) = mergeRewatchRemoteWithLedger(
        sessions = sessions, ledger = ledger, profileId = 2,
        simklAccountId = simklAccount, deviceId = device,
        expectedLocal = local, expectedGeneration = generation,
        canonicalSeriesId = original.canonicalSeriesId,
        remoteLookup = remote, pendingOrUnpublished = pending,
        appliedAtEpochMs = 800
    )

    @Test fun exactAuthenticatedGetCertifiesCleanAndSurvivesSerialization() {
        val ledger = clean()
        val restored = validateRewatchLocalSyncLedger(
            Json.decodeFromString<RewatchLocalSyncLedger>(Json.encodeToString(ledger)), 2
        )
        assertEquals(ledger, restored)
        assertEquals(5L, restored.cleanBySeries.values.single().revision)
        assertEquals(3L, restored.cleanBySeries.values.single().generation)
        assertTrue(sameRewatchCursor(
            original, restored.cleanBySeries.values.single().cursor
        ))
    }

    @Test fun localReplayCommitInvalidatesProofInSameLedgerMutation() {
        val ledger = clean()
        val changed = onLocalRewatchCommitted(ledger,
            original.copy(positionMs = 70_000, updatedAtEpochMs = 200))
        assertEquals(4L, changed.generation)
        assertTrue(changed.cleanBySeries.isEmpty())
        assertNull(apply(ledger = changed, generation = changed.generation))
    }

    @Test fun verifiedNewerRemoteNextUpAtomicallyUpdatesCursorAndCleanProof() {
        val updated = requireNotNull(apply())
        assertEquals(RewatchEpisode(3, 19), updated.first.single().episode)
        assertEquals(RewatchPhase.NEXT_UP, updated.first.single().phase)
        assertEquals(4L, updated.second.generation)
        val proof = updated.second.cleanBySeries.values.single()
        assertEquals(6L, proof.revision)
        assertEquals(4L, proof.generation)
        assertTrue(proof.cursor == updated.first.single())
    }

    @Test fun laterLocalWriteRejectsRemoteResponseEvenIfSessionReturnedToSameValue() {
        // Progress may move forward and later back via a seek. Exact equality
        // of the cursor alone is insufficient; the durable generation matters.
        val afterAba = onLocalRewatchCommitted(
            onLocalRewatchCommitted(clean(), original.copy(positionMs = 90_000)),
            original
        )
        assertEquals(original, listOf(original).single())
        assertNull(apply(ledger = afterAba, generation = 3))
    }

    @Test fun absentCleanProofCannotOverwriteExistingLocalReplay() {
        assertNull(apply(ledger = RewatchLocalSyncLedger(generation = 3)))
    }

    @Test fun pendingOrUnpublishedLocalEventPreventsRemoteWrite() {
        assertNull(apply(pending = true))
    }

    @Test fun alternateSimklAccountAndWrongProfileCannotUseCleanProof() {
        assertNull(apply(simklAccount = "99221"))
        val otherProfile = original.copy(ownerKey = "local-profile:3")
        assertNull(apply(
            sessions = listOf(otherProfile), local = otherProfile
        ))
    }

    @Test fun remoteStaleRevisionCannotRewindNextUp() {
        assertNull(apply(remote = verified(nextUp, 5)))
        assertNull(apply(remote = verified(nextUp, 4)))
    }

    @Test fun cleanCertifyRejectsUnmatchedRemoteAndPendingWrites() {
        val ledger = RewatchLocalSyncLedger(generation = 3)
        assertNull(certifyRewatchLocalClean(
            listOf(original), ledger, 2, account, device, original,
            ledger.generation, verified(original.copy(positionMs = 88_000)), false
        ))
        assertNull(certifyRewatchLocalClean(
            listOf(original), ledger, 2, account, device, original,
            ledger.generation, verified(), true
        ))
    }

    @Test fun freshDeviceCanImportRemoteButLateLocalSavePreventsIt() {
        val emptyLedger = RewatchLocalSyncLedger(generation = 0)
        val imported = requireNotNull(apply(
            sessions = emptyList(), ledger = emptyLedger,
            local = null, generation = 0, remote = verified(nextUp, 6)
        ))
        assertEquals(RewatchEpisode(3, 19), imported.first.single().episode)
        assertEquals(1L, imported.second.generation)
        assertNull(apply(
            sessions = listOf(original), ledger = RewatchLocalSyncLedger(generation = 1),
            local = null, generation = 0
        ))
    }

    @Test fun malformedCleanProofFailsClosedInsteadOfResetting() {
        val saved = Json { encodeDefaults = true }.encodeToString(clean())
        assertFailsWith<IllegalArgumentException> {
            validateRewatchLocalSyncLedger(Json.decodeFromString<RewatchLocalSyncLedger>(
                saved.replace("local-profile:2", "local-profile:3")), 2
            )
        }
        assertFailsWith<IllegalArgumentException> {
            validateRewatchLocalSyncLedger(Json.decodeFromString<RewatchLocalSyncLedger>(
                saved.replace("\"schemaVersion\":1", "\"schemaVersion\":99")), 2
            )
        }
    }

    @Test fun unrelatedSeriesSaveRetainsOtherCleanProofButChangesGeneration() {
        val ledger = clean()
        val unrelated = original.copy(canonicalSeriesId = "imdb:ttother")
        val changed = onLocalRewatchCommitted(ledger, unrelated)
        assertEquals(4L, changed.generation)
        assertEquals(1, changed.cleanBySeries.size)
        // Proof remains available but is no longer current until recertified
        assertNull(apply(ledger = changed, generation = changed.generation))
    }
}
