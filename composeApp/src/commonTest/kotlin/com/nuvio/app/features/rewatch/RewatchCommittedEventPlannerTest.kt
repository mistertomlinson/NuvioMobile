package com.nuvio.app.features.rewatch

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RewatchCommittedEventPlannerTest {
    private val s3e5 = RewatchEpisode(3, 5)
    private val s3e6 = RewatchEpisode(3, 6)
    private val original = RewatchSession(
        ownerKey = "local-profile:2",
        canonicalSeriesId = "imdb:tt0386676",
        sessionId = "office-replay",
        episode = s3e5,
        positionMs = 0,
        durationMs = 0,
        lastCompletedEpisode = null,
        phase = RewatchPhase.PLAYING,
        updatedAtEpochMs = 100
    )
    private val device = "simkl-device:4da37c48-6caf-4f87-8f87-89061db61188"
    private fun plan(
        before: RewatchSession?,
        after: RewatchSession,
        checkpoint: RewatchRemoteCheckpoint
    ): RewatchQueuedEvent? = planCommittedRewatchEvent(
        before, after, checkpoint, "event-id-1", device
    )
    private fun data(value: RewatchQueuedEvent) =
        Json.parseToJsonElement(value.payload).jsonObject

    @Test
    fun unknownServerStateNeverPublishesAnEvent() {
        assertNull(plan(null, original, RewatchRemoteCheckpoint.Unknown))
        assertNull(plan(original, original.copy(positionMs = 22000),
            RewatchRemoteCheckpoint.Unknown))
    }

    @Test
    fun confirmedEmptyImportPreservesAlreadyWatchedNextUpWithoutLocalOwnerId() {
        val local = original.copy(
            episode = RewatchEpisode(3, 18),
            lastCompletedEpisode = RewatchEpisode(3, 17),
            phase = RewatchPhase.NEXT_UP
        )
        val event = requireNotNull(plan(null, local,
            RewatchRemoteCheckpoint.ConfirmedEmpty))
        val body = data(event)
        assertEquals("IMPORT_SNAPSHOT", body["type"]?.jsonPrimitive?.content)
        assertEquals("0", body["expectedRevision"]?.toString())
        assertEquals("NEXT_UP", body["session"]?.jsonObject
            ?.get("phase")?.jsonPrimitive?.content)
        assertEquals("18", body["session"]?.jsonObject
            ?.get("episode")?.jsonObject?.get("episode")?.toString())
        assertTrue("ownerKey" !in event.payload)
        assertTrue("local-profile:2" !in event.payload)
    }

    @Test
    fun firstImportCanPreservePausedProgressAndMetadataPendingState() {
        val paused = requireNotNull(plan(null,
            original.copy(positionMs = 65000, durationMs = 1800000),
            RewatchRemoteCheckpoint.ConfirmedEmpty))
        assertEquals("65000", data(paused)["session"]?.jsonObject
            ?.get("positionMs")?.toString())
        val awaiting = requireNotNull(plan(null, original.copy(
            phase = RewatchPhase.AWAITING_METADATA,
            lastCompletedEpisode = s3e5
        ), RewatchRemoteCheckpoint.ConfirmedEmpty))
        assertEquals("AWAITING_METADATA", data(awaiting)["session"]?.jsonObject
            ?.get("phase")?.jsonPrimitive?.content)
    }

    @Test
    fun genuineCompletedEpisodeCreatesVerifiedNextUpEvent() {
        val next = original.copy(
            episode = s3e6,
            lastCompletedEpisode = s3e5,
            phase = RewatchPhase.NEXT_UP,
            updatedAtEpochMs = 300
        )
        val event = requireNotNull(plan(original, next,
            RewatchRemoteCheckpoint.Synced(4, original.sessionId)))
        val body = data(event)
        assertEquals("COMPLETE_EPISODE", body["type"]?.jsonPrimitive?.content)
        assertEquals("4", body["expectedRevision"]?.toString())
        assertEquals("NEXT_UP", body["resultPhase"]?.jsonPrimitive?.content)
        assertEquals("6", body["nextEpisode"]?.jsonObject
            ?.get("episode")?.toString())
    }

    @Test
    fun manualForwardAndBackwardSelectionPreservesSessionAndProducesReposition() {
        for (target in listOf(RewatchEpisode(5, 18), RewatchEpisode(2, 1))) {
            val moved = original.copy(
                episode = target, positionMs = 0L, durationMs = 0L,
                lastCompletedEpisode = null, phase = RewatchPhase.PLAYING,
                updatedAtEpochMs = 400
            )
            val planned = requireNotNull(plan(original, moved,
                RewatchRemoteCheckpoint.Synced(11, original.sessionId)))
            val body = data(planned)
            assertEquals("REPOSITION", body["type"]?.jsonPrimitive?.content)
            assertEquals("11", body["expectedRevision"]?.toString())
            assertEquals(target.season.toString(),
                body["episode"]?.jsonObject?.get("season")?.toString())
            assertEquals(original.sessionId, planned.sessionId)
            assertTrue("ownerKey" !in planned.payload)
        }
    }

    @Test
    fun explicitDetailsResumeCarriesOffsetToOtherDevices() {
        val moved = original.copy(
            episode = RewatchEpisode(5, 19),
            positionMs = 391_000L,
            durationMs = 0L,
            lastCompletedEpisode = null,
            phase = RewatchPhase.PLAYING
        )
        val queued = requireNotNull(plan(original, moved,
            RewatchRemoteCheckpoint.Synced(17, original.sessionId)))
        val payload = data(queued)
        assertEquals("REPOSITION", payload["type"]?.jsonPrimitive?.content)
        assertEquals("391000", payload["positionMs"]?.toString())
        assertEquals("0", payload["durationMs"]?.toString())
    }

    @Test
    fun autoplayCompletionAndArbitraryProgressNeverForgeReposition() {
        val next = original.copy(
            episode = s3e6,
            phase = RewatchPhase.NEXT_UP,
            lastCompletedEpisode = s3e5,
            positionMs = 0L,
            durationMs = 0L
        )
        val completion = requireNotNull(plan(original, next,
            RewatchRemoteCheckpoint.Synced(7, original.sessionId)))
        assertEquals("COMPLETE_EPISODE", data(completion)["type"]?.jsonPrimitive?.content)
        assertNull(plan(original, original.copy(
            episode = s3e6, phase = RewatchPhase.PLAYING,
            positionMs = 3_000, lastCompletedEpisode = null
        ), RewatchRemoteCheckpoint.Synced(7, original.sessionId)))
    }

    @Test
    fun unknownMetadataDoesNotFalselyFinishTheSeries() {
        val awaiting = original.copy(
            phase = RewatchPhase.AWAITING_METADATA,
            lastCompletedEpisode = s3e5
        )
        val event = requireNotNull(plan(original, awaiting,
            RewatchRemoteCheckpoint.Synced(1, original.sessionId)))
        val body = data(event)
        assertEquals("COMPLETE_EPISODE", body["type"]?.jsonPrimitive?.content)
        assertEquals("AWAITING_METADATA", body["resultPhase"]?.jsonPrimitive?.content)
        assertEquals(null, body["nextEpisode"])
    }

    @Test
    fun laterVerifiedMetadataResolvesSuccessorWithDedicatedTransition() {
        val awaiting = original.copy(
            phase = RewatchPhase.AWAITING_METADATA,
            lastCompletedEpisode = s3e5
        )
        val next = awaiting.copy(
            episode = s3e6,
            phase = RewatchPhase.NEXT_UP
        )
        val event = requireNotNull(plan(awaiting, next,
            RewatchRemoteCheckpoint.Synced(2, original.sessionId)))
        val body = data(event)
        assertEquals("RESOLVE_METADATA", body["type"]?.jsonPrimitive?.content)
        assertEquals("6", body["nextEpisode"]?.jsonObject
            ?.get("episode")?.toString())
        assertEquals("5", body["completedEpisode"]?.jsonObject
            ?.get("episode")?.toString())
    }

    @Test
    fun zeroPositionNextUpCallbackIsNeverTreatedAsActualPlayback() {
        val previous = original.copy(
            phase = RewatchPhase.NEXT_UP,
            episode = s3e6,
            lastCompletedEpisode = s3e5
        )
        assertNull(plan(previous,
            previous.copy(phase = RewatchPhase.PLAYING, positionMs = 0),
            RewatchRemoteCheckpoint.Synced(3, previous.sessionId)))
        val started = requireNotNull(plan(previous,
            previous.copy(phase = RewatchPhase.PLAYING, positionMs = 1200),
            RewatchRemoteCheckpoint.Synced(3, previous.sessionId)))
        assertEquals("PROGRESS", data(started)["type"]?.jsonPrimitive?.content)
    }

    @Test
    fun explicitDismissalWritesStopTombstoneNotNewWatchedHistory() {
        val stopped = requireNotNull(plan(original,
            original.copy(phase = RewatchPhase.FINISHED),
            RewatchRemoteCheckpoint.Synced(1, original.sessionId)))
        assertEquals("STOP", data(stopped)["type"]?.jsonPrimitive?.content)
        assertEquals(null, data(stopped)["session"])
    }

    @Test
    fun restartingFinishedSeriesCanStartFreshSessionOnlyWithKnownRevision() {
        val finished = original.copy(phase = RewatchPhase.FINISHED)
        val started = original.copy(
            sessionId = "new-office-replay", updatedAtEpochMs = 500
        )
        val event = requireNotNull(plan(finished, started,
            RewatchRemoteCheckpoint.Synced(9, finished.sessionId)))
        assertEquals("START", data(event)["type"]?.jsonPrimitive?.content)
        assertEquals("9", data(event)["expectedRevision"]?.toString())
    }

    @Test
    fun profileSwitchAndWrongRemoteSessionNeverGenerateEvent() {
        val progress = original.copy(positionMs = 5000)
        assertNull(plan(original, progress.copy(ownerKey = "local-profile:3"),
            RewatchRemoteCheckpoint.Synced(1, original.sessionId)))
        assertNull(plan(original, progress,
            RewatchRemoteCheckpoint.Synced(1, "different-replay-session")))
        assertNull(plan(original, original,
            RewatchRemoteCheckpoint.Synced(1, original.sessionId)))
    }

    @Test
    fun fakeSuccessorAndInvalidMetadataDoNotAdvanceOtherDevices() {
        val invalidNext = original.copy(
            phase = RewatchPhase.NEXT_UP,
            episode = s3e5,
            lastCompletedEpisode = s3e5
        )
        assertNull(plan(original, invalidNext,
            RewatchRemoteCheckpoint.Synced(1, original.sessionId)))
        val badAwaiting = original.copy(
            phase = RewatchPhase.AWAITING_METADATA,
            lastCompletedEpisode = RewatchEpisode(3, 4)
        )
        assertNull(plan(original, badAwaiting,
            RewatchRemoteCheckpoint.Synced(1, original.sessionId)))
    }
}
