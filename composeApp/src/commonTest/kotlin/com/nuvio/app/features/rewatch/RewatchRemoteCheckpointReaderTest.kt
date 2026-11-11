package com.nuvio.app.features.rewatch

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class RewatchRemoteCheckpointReaderTest {
    private val series = "imdb:tt0386676"
    private val owner = "local-profile:2"
    private val nextUp = """
      {"schemaVersion":1,"items":[{
         "revision":9,
         "session":{
            "sessionId":"office-replay",
            "canonicalSeriesId":"imdb:tt0386676",
            "episode":{"season":3,"episode":18},
            "positionMs":0,"durationMs":0,
            "lastCompletedEpisode":{"season":3,"episode":17},
            "phase":"NEXT_UP"
         }
      }]}
    """.trimIndent()

    @Test
    fun emptyAuthenticatedResponseIsOnlyWayToProveEmptySlot() {
        val result = readVerifiedRewatchCheckpoint(
            """{"schemaVersion":1,"items":[]}""", series, owner
        )
        val verified = assertIs<RewatchRemoteLookup.Verified>(result)
        assertEquals(RewatchRemoteCheckpoint.ConfirmedEmpty, verified.checkpoint)
        assertEquals(null, verified.remoteSession)
    }

    @Test
    fun verifiedNextUpCreatesRevisionBoundCheckpointAndLocalOwnerIdentity() {
        val verified = assertIs<RewatchRemoteLookup.Verified>(
            readVerifiedRewatchCheckpoint(nextUp, series, owner)
        )
        assertEquals(RewatchRemoteCheckpoint.Synced(9, "office-replay"),
            verified.checkpoint)
        assertEquals(owner, verified.remoteSession?.ownerKey)
        assertEquals(RewatchEpisode(3, 18), verified.remoteSession?.episode)
        assertEquals(RewatchEpisode(3, 17),
            verified.remoteSession?.lastCompletedEpisode)
        assertEquals(RewatchPhase.NEXT_UP, verified.remoteSession?.phase)
        assertEquals(0L, verified.remoteSession?.updatedAtEpochMs)
    }

    @Test
    fun otherSeriesDoesNotCreateFalseConflict() {
        val result = readVerifiedRewatchCheckpoint(
            nextUp, "imdb:tt0944947", owner
        )
        val verified = assertIs<RewatchRemoteLookup.Verified>(result)
        assertEquals(RewatchRemoteCheckpoint.ConfirmedEmpty, verified.checkpoint)
    }

    @Test
    fun wrongSchemaMissingRowsAndMalformedPayloadNeverMeanEmpty() {
        for (payload in listOf(
            "not-json",
            "{}",
            """{"schemaVersion":2,"items":[]}""",
            """{"schemaVersion":1}""",
            """{"schemaVersion":1,"items":null}""",
            """{"schemaVersion":1,"items":[{"revision":0}]}"""
        )) {
            assertIs<RewatchRemoteLookup.Invalid>(
                readVerifiedRewatchCheckpoint(payload, series, owner))
        }
    }

    @Test
    fun ownerCannotBeAssertedInRemoteResponseOrLocalProfileZero() {
        val impersonation = nextUp.replace(
            "\"sessionId\":\"office-replay\"",
            "\"ownerKey\":\"local-profile:9999\",\"sessionId\":\"office-replay\""
        )
        assertIs<RewatchRemoteLookup.Invalid>(
            readVerifiedRewatchCheckpoint(impersonation, series, owner))
        assertIs<RewatchRemoteLookup.Invalid>(
            readVerifiedRewatchCheckpoint(nextUp, series, "local-profile:0"))
    }

    @Test
    fun duplicateRemoteSeriesRowsAreAmbiguousAndNotImported() {
        val item = nextUp.substringAfter("[").substringBeforeLast("]")
        val duplicated = """{"schemaVersion":1,"items":[$item,$item]}"""
        assertIs<RewatchRemoteLookup.Invalid>(
            readVerifiedRewatchCheckpoint(duplicated, series, owner))
    }

    @Test
    fun semanticallyImpossibleNextUpIsNeverTrusted() {
        val invalid = nextUp.replace(
            "\"episode\":17", "\"episode\":18"
        )
        assertIs<RewatchRemoteLookup.Invalid>(
            readVerifiedRewatchCheckpoint(invalid, series, owner))
        val resumedAtZero = nextUp.replace(
            "\"positionMs\":0", "\"positionMs\":7000"
        )
        assertIs<RewatchRemoteLookup.Invalid>(
            readVerifiedRewatchCheckpoint(resumedAtZero, series, owner))
    }

    @Test
    fun remoteStatusCannotFabricateUnknownPhase() {
        val invalid = nextUp.replace("\"NEXT_UP\"", "\"UNKNOWN\"")
        assertIs<RewatchRemoteLookup.Invalid>(
            readVerifiedRewatchCheckpoint(invalid, series, owner))
    }

    @Test
    fun serverPayloadCannotOverwriteLocalExistingReplayOnMissingRevision() {
        val invalid = nextUp.replace("\"revision\":9,", "")
        assertIs<RewatchRemoteLookup.Invalid>(
            readVerifiedRewatchCheckpoint(invalid, series, owner))
    }
}
