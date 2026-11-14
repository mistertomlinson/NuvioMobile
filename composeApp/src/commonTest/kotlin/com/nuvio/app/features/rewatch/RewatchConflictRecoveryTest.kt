package com.nuvio.app.features.rewatch

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Reproduces the device's rev-1 Office queue: remote S5E6, a legacy
 * PROGRESS(S5E19) rejected as invalid_progress, then an explicitly selected
 * new episode. No Simkl calls or VPS traffic are made by these tests.
 */
class RewatchConflictRecoveryTest {
    private class MemoryStorage : RewatchOutboxStorage {
        var json: String? = null
        override suspend fun read(profileId: Int): String? = json
        override suspend fun write(profileId: Int, payload: String) { json = payload }
        fun saved() = Json.decodeFromString<RewatchOutboxEnvelope>(requireNotNull(json))
    }

    private val profile = 2
    private val account = "12003"
    private val device = "simkl-device:ipad-repair"
    private val sid = "ios-2-12345"
    private val series = "tt0386676"

    private val remote = RewatchSession(
        ownerKey = "local-profile:2",
        canonicalSeriesId = series,
        sessionId = sid,
        episode = RewatchEpisode(5, 6),
        positionMs = 257424,
        durationMs = 1307978,
        lastCompletedEpisode = null,
        phase = RewatchPhase.PLAYING,
        updatedAtEpochMs = 0
    )
    private val selectedBefore = remote.copy(
        episode = RewatchEpisode(5, 19),
        positionMs = 25000,
        updatedAtEpochMs = 100
    )
    private val selectedAfter = selectedBefore.copy(
        episode = RewatchEpisode(5, 18),
        positionMs = 0,
        durationMs = 0,
        lastCompletedEpisode = null,
        updatedAtEpochMs = 200
    )
    private val latest = selectedAfter.copy(
        positionMs = 23000,
        durationMs = 1307978,
        updatedAtEpochMs = 300
    )
    private fun lookup(session: RewatchSession = remote, revision: Long = 1) =
        RewatchRemoteLookup.Verified(
            RewatchRemoteCheckpoint.Synced(revision, session.sessionId),
            session
        )
    private fun legacy(
        id: String = "blocked-progress",
        type: String = "PROGRESS",
        revision: Long = 1,
        episode: Int = 19
    ): RewatchQueuedEvent {
        val payload = """
            {"eventId":"$id","deviceId":"$device",
             "canonicalSeriesId":"$series","sessionId":"$sid",
             "expectedRevision":$revision,"type":"$type",
             "episode":{"season":5,"episode":$episode},
             "positionMs":12000,"durationMs":1307978}
        """.trimIndent()
        return RewatchQueuedEvent(
            eventId = id, deviceId = device, canonicalSeriesId = series,
            sessionId = sid, expectedRevision = revision, payload = payload
        )
    }
    private fun queue(storage: MemoryStorage,
        current: (Int, String, String) -> Boolean = { _, _, _ -> true }
    ) = RewatchSyncOutbox(storage, current)

    private suspend fun recover(
        queue: RewatchSyncOutbox,
        before: RewatchSession = selectedBefore,
        after: RewatchSession = selectedAfter,
        current: RewatchSession = latest,
        verified: RewatchRemoteLookup = lookup(),
        unchanged: suspend () -> Boolean = { true }
    ) = queue.recoverExplicitManualSelection(
        profileId = profile, simklAccountId = account, deviceId = device,
        manualBefore = before, manualAfter = after, latestLocal = current,
        lookup = verified, repositionEventId = "repaired-reposition",
        progressEventId = "repaired-progress", localUnchanged = unchanged
    )

    @Test
    fun blockedProgressIsArchivedAndManualJumpPlusCurrentPositionArePublished() =
        runBlocking {
            val storage = MemoryStorage()
            val queue = queue(storage)
            assertTrue(queue.enqueue(profile, account, device, legacy()))
            assertTrue(queue.enqueue(profile, account, device,
                legacy("newer-manual", "REPOSITION", 2, 18)))
            assertTrue(recover(queue))

            val saved = storage.saved()
            assertEquals(listOf("blocked-progress", "newer-manual"),
                saved.recoveryArchive.map { it.eventId })
            assertEquals(2, saved.pending.size)
            assertEquals(listOf(1L, 2L),
                saved.pending.map { it.expectedRevision })
            val types = saved.pending.map {
                Json.parseToJsonElement(it.payload).jsonObject["type"]
                    ?.jsonPrimitive?.content
            }
            assertEquals(listOf("REPOSITION", "PROGRESS"), types)
            val progress = Json.parseToJsonElement(
                saved.pending.last().payload
            ).jsonObject
            assertEquals("23000", progress["positionMs"].toString())
            assertEquals("1307978", progress["durationMs"].toString())

            assertEquals(RewatchOutboxDrainResult.DRAINED,
                queue.drain(profile, account, device) {
                    RewatchOutboxSubmitResult.Accepted(it.expectedRevision + 1)
                })
            assertEquals(0, queue.pendingCount(profile, account, device))
            assertEquals(2, storage.saved().recoveryArchive.size)
        }

    @Test
    fun rejectedHeadForSameEpisodeIsNotTreatedAsManualJump() = runBlocking {
        val storage = MemoryStorage()
        val queue = queue(storage)
        queue.enqueue(profile, account, device, legacy(episode = 6))
        val original = storage.json
        assertFalse(recover(queue))
        assertEquals(original, storage.json)
    }

    @Test
    fun onlyAFreshMatchingRemoteRevisionMayReanchorOutbox() = runBlocking {
        val storage = MemoryStorage()
        val queue = queue(storage)
        queue.enqueue(profile, account, device, legacy())
        val original = storage.json
        assertFalse(recover(queue, verified = lookup(revision = 2)))
        assertFalse(recover(queue, verified = lookup(remote.copy(
            sessionId = "new-remote-session"
        ))))
        assertFalse(recover(queue, verified = RewatchRemoteLookup.Invalid))
        assertEquals(original, storage.json)
    }

    @Test
    fun noRecoveryWithoutARealNewManualEpisodeSelection() = runBlocking {
        val storage = MemoryStorage()
        val queue = queue(storage)
        queue.enqueue(profile, account, device, legacy())
        val original = storage.json
        assertFalse(recover(queue, after = selectedBefore))
        assertFalse(recover(queue, after = selectedAfter.copy(
            durationMs = 1307978
        )))
        assertFalse(recover(queue, after = selectedAfter.copy(
            sessionId = "different-session"
        )))
        assertFalse(recover(queue, current = latest.copy(
            episode = RewatchEpisode(5, 20)
        )))
        assertEquals(original, storage.json)
    }

    @Test
    fun profileSwitchOrConcurrentSaveLeavesEntireOutboxUnchanged() = runBlocking {
        val storage = MemoryStorage()
        val queue = queue(storage)
        queue.enqueue(profile, account, device, legacy())
        val original = storage.json
        assertFalse(recover(queue, unchanged = { false }))
        assertEquals(original, storage.json)
        var current = true
        assertFalse(recover(queue, unchanged = {
            val value = current
            current = false
            value
        }))
        assertEquals(original, storage.json)
        val differentAccount = RewatchSyncOutbox(storage) { _, id, _ -> id == "other" }
        assertFalse(recover(differentAccount))
        assertEquals(original, storage.json)
    }

    @Test
    fun mixedCompletionQueueMustNotLoseOfflineHistory() = runBlocking {
        val storage = MemoryStorage()
        val queue = queue(storage)
        queue.enqueue(profile, account, device, legacy())
        queue.enqueue(profile, account, device,
            legacy("offline-completion", "COMPLETE_EPISODE", 2))
        val original = storage.json
        assertFalse(recover(queue))
        assertEquals(original, storage.json)
        assertEquals(2, queue.pendingCount(profile, account, device))
    }

    @Test
    fun unrelatedSeriesAtTheQueueHeadMustNotTriggerOfficeRecovery() = runBlocking {
        val storage = MemoryStorage()
        val queue = queue(storage)
        val other = legacy("other-show-head").copy(
            canonicalSeriesId = "tt1111111"
        )
        queue.enqueue(profile, account, device, other)
        queue.enqueue(profile, account, device, legacy())
        val original = storage.json
        assertFalse(recover(queue))
        assertEquals(original, storage.json)
    }

    @Test
    fun typedInvalidProgressNeverDropsTheHeadWithoutManualRecovery() = runBlocking {
        val storage = MemoryStorage()
        val queue = queue(storage)
        queue.enqueue(profile, account, device, legacy())
        assertEquals(RewatchOutboxDrainResult.INVALID_PROGRESS,
            queue.drain(profile, account, device) {
                RewatchOutboxSubmitResult.InvalidProgress
            })
        assertEquals(1, queue.pendingCount(profile, account, device))
        assertTrue(storage.saved().recoveryArchive.isEmpty())
    }
}
