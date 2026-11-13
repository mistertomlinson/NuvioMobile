package com.nuvio.app.features.rewatch

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class RewatchForegroundSynchronizerTest {
private val office = RewatchSession(
    ownerKey = "local-profile:2",
    canonicalSeriesId = "imdb:tt0386676",
    sessionId = "office-replay",
    episode = RewatchEpisode(3, 18),
    positionMs = 0L, durationMs = 0L,
    lastCompletedEpisode = RewatchEpisode(3, 17),
    phase = RewatchPhase.NEXT_UP,
    updatedAtEpochMs = 100L
)

private inner class FakeSyncAccess : RewatchForegroundAccess {
    val who = RewatchCommitIdentity(2, "12003", "simkl-device:test")
    var current = true
    var visible: List<String>? = emptyList()
    var local = emptyList<RewatchSession>()
    var generation = 0L
    var imported = 0
    var applied = 0
    var uploaded = 0
    var pending = false
    var drainResult = RewatchOutboxDrainResult.DRAINED
    var remoteEmpty = true
    var changedDuringRead = false
    var reads = 0
    override suspend fun connect() = who
    override fun stillCurrent(identity: RewatchCommitIdentity) =
        current && identity == who
    override suspend fun remoteSeriesIds(): List<String>? {
        if (changedDuringRead) current = false
        return visible
    }
    override suspend fun observeLocal(profileId: Int): RewatchObservedLocalSync {
        reads++
        return RewatchObservedLocalSync(profileId, local, generation)
    }
    override suspend fun applyRemote(seriesId: String): Boolean {
        applied++
        if (local.isEmpty()) {
            local = listOf(office)
            generation++
        }
        return true
    }
    override suspend fun checkpoint(seriesId: String): RewatchRemoteLookup =
        if (remoteEmpty) RewatchRemoteLookup.Verified(
            RewatchRemoteCheckpoint.ConfirmedEmpty, null
        ) else RewatchRemoteLookup.Verified(
            RewatchRemoteCheckpoint.Synced(2, office.sessionId), office
        )
    override suspend fun hasPending(
        identity: RewatchCommitIdentity, seriesId: String
    ) = pending
    override suspend fun enqueueImport(
        identity: RewatchCommitIdentity, session: RewatchSession
    ): Boolean {
        if (identity != who || session != office) return false
        imported++
        return true
    }
    override suspend fun upload(): RewatchOutboxDrainResult {
        uploaded++
        return drainResult
    }
}

@Test
fun discoversRemoteOnlyOfficeRewatchBeforeLocalHistory() = runBlocking {
    val fake = FakeSyncAccess().apply { visible = listOf(office.canonicalSeriesId) }
    assertEquals(RewatchForegroundResult.COMPLETED,
        RewatchForegroundSynchronizer(fake).reconcile())
    assertEquals(listOf(office), fake.local)
    assertEquals(1, fake.applied)
    assertEquals(0, fake.imported)
    assertEquals(1, fake.uploaded)
}

@Test
fun importsDurableLocalNextUpOnlyAfterAuthenticatedEmptyCheckpoint() = runBlocking {
    val fake = FakeSyncAccess().apply { local = listOf(office) }
    assertEquals(RewatchForegroundResult.COMPLETED,
        RewatchForegroundSynchronizer(fake).reconcile())
    assertEquals(1, fake.imported)
    assertEquals(2, fake.uploaded)
    assertEquals(3, fake.reads)
}

@Test
fun newlyPublishedRemoteSessionNeverGetsOverwrittenByOfflineImport() = runBlocking {
    val fake = FakeSyncAccess().apply {
        local = listOf(office)
        remoteEmpty = false
    }
    assertEquals(RewatchForegroundResult.LOCAL_CONFLICT,
        RewatchForegroundSynchronizer(fake).reconcile())
    assertEquals(0, fake.imported)
    assertEquals(1, fake.uploaded)
}

@Test
fun pendingOfflineEventsArePreservedInsteadOfDuplicatingImport() = runBlocking {
    val fake = FakeSyncAccess().apply {
        local = listOf(office)
        pending = true
    }
    assertEquals(RewatchForegroundResult.COMPLETED,
        RewatchForegroundSynchronizer(fake).reconcile())
    assertEquals(0, fake.imported)
    assertEquals(1, fake.uploaded)
}

@Test
fun accountSwitchAndFailedRosterNeverImportOrUpload() = runBlocking {
    val switched = FakeSyncAccess().apply {
        local = listOf(office)
        changedDuringRead = true
    }
    assertEquals(RewatchForegroundResult.PROFILE_CHANGED,
        RewatchForegroundSynchronizer(switched).reconcile())
    assertEquals(0, switched.imported)
    assertEquals(1, switched.uploaded)

    val unavailable = FakeSyncAccess().apply {
        local = listOf(office)
        visible = null
    }
    assertEquals(RewatchForegroundResult.UNAVAILABLE,
        RewatchForegroundSynchronizer(unavailable).reconcile())
    assertEquals(0, unavailable.imported)
    assertEquals(1, unavailable.uploaded)
}

@Test
fun offlineConflictStopsBeforeRemotePullAndPreservesQueuedState() = runBlocking {
    val fake = FakeSyncAccess().apply {
        local = listOf(office)
        visible = listOf(office.canonicalSeriesId)
        pending = true
        drainResult = RewatchOutboxDrainResult.CONFLICT
    }
    assertEquals(RewatchForegroundResult.LOCAL_CONFLICT,
        RewatchForegroundSynchronizer(fake).reconcile())
    assertEquals(listOf(office), fake.local)
    assertEquals(0, fake.applied)
    assertEquals(0, fake.imported)
    assertEquals(1, fake.uploaded)
}

@Test
fun onlyRequestedSeriesIsProcessedOnPreplayAndOtherShowsStayUntouched() = runBlocking {
    val fake = FakeSyncAccess().apply {
        visible = listOf(office.canonicalSeriesId)
        local = emptyList()
    }
    assertEquals(RewatchForegroundResult.COMPLETED,
        RewatchForegroundSynchronizer(fake).reconcile("imdb:tt0944947"))
    assertEquals(0, fake.applied)
    assertEquals(0, fake.imported)
}

}
