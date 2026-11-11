package com.nuvio.app.features.rewatch

import kotlinx.serialization.Serializable

/**
 * Part of the SAME profile DataStore transaction as the replay cursor.
 * Unlike the transport outbox, this is the proof that no local player save
 * has occurred since a successfully verified synchronization.
 *
 * No access token, watched history or PIN is ever stored here.
 */
@Serializable
internal data class RewatchLocalCleanProof(
    val simklAccountId: String,
    val deviceId: String,
    val revision: Long,
    val generation: Long,
    val cursor: RewatchSession
)

@Serializable
internal data class RewatchLocalSyncLedger(
    val schemaVersion: Int = 1,
    val generation: Long = 0L,
    val cleanBySeries: Map<String, RewatchLocalCleanProof> = emptyMap()
)

private fun key(series: String) = series.trim().lowercase()

internal fun sameRewatchCursor(a: RewatchSession, b: RewatchSession): Boolean =
    a.ownerKey == b.ownerKey &&
        key(a.canonicalSeriesId) == key(b.canonicalSeriesId) &&
        a.sessionId == b.sessionId &&
        a.episode == b.episode &&
        a.positionMs == b.positionMs &&
        a.durationMs == b.durationMs &&
        a.lastCompletedEpisode == b.lastCompletedEpisode &&
        a.phase == b.phase

internal fun validateRewatchLocalSyncLedger(
    ledger: RewatchLocalSyncLedger,
    profileId: Int
): RewatchLocalSyncLedger {
    require(profileId > 0)
    require(ledger.schemaVersion == 1 &&
        ledger.generation >= 0L && ledger.generation < Long.MAX_VALUE
    ) { "Invalid local replay sync ledger" }
    require(ledger.cleanBySeries.all { (series, proof) ->
        series.isNotBlank() && series.length <= 160 && series == key(series) &&
            proof.simklAccountId.matches(Regex("[1-9][0-9]{0,15}")) &&
            proof.deviceId.isNotBlank() && proof.deviceId.length <= 160 &&
            proof.revision > 0L && proof.revision < Long.MAX_VALUE &&
            proof.generation <= ledger.generation && proof.generation >= 0L &&
            proof.cursor.ownerKey == "local-profile:$profileId" &&
            key(proof.cursor.canonicalSeriesId) == series &&
            proof.cursor.sessionId.isNotBlank() &&
            proof.cursor.episode.season > 0 && proof.cursor.episode.episode > 0 &&
            proof.cursor.positionMs >= 0L && proof.cursor.durationMs >= 0L
    }) { "Invalid local replay clean proof" }
    return ledger
}

/** Called as part of every accepted local DataStore replay update. */
internal fun onLocalRewatchCommitted(
    old: RewatchLocalSyncLedger,
    replay: RewatchSession
): RewatchLocalSyncLedger {
    require(old.generation < Long.MAX_VALUE - 1)
    return old.copy(
        generation = old.generation + 1L,
        cleanBySeries = old.cleanBySeries - key(replay.canonicalSeriesId)
    )
}

/** Pure proof calculation. Caller saves it inside the replay DataStore edit. */
internal fun certifyRewatchLocalClean(
    sessions: List<RewatchSession>,
    old: RewatchLocalSyncLedger,
    profileId: Int,
    simklAccountId: String,
    deviceId: String,
    localSnapshot: RewatchSession,
    expectedGeneration: Long,
    remoteLookup: RewatchRemoteLookup,
    hasPendingOrUnpublishedChanges: Boolean
): RewatchLocalSyncLedger? {
    if (profileId <= 0 || old.generation != expectedGeneration ||
        hasPendingOrUnpublishedChanges ||
        localSnapshot.ownerKey != "local-profile:$profileId" ||
        simklAccountId.isBlank() || deviceId.isBlank()) return null
    val verified = remoteLookup as? RewatchRemoteLookup.Verified ?: return null
    val checkpoint = verified.checkpoint as? RewatchRemoteCheckpoint.Synced ?: return null
    val remote = verified.remoteSession ?: return null
    val matching = sessions.filter { key(it.canonicalSeriesId) ==
        key(localSnapshot.canonicalSeriesId) }
    if (matching.size != 1 || matching.single() != localSnapshot ||
        !sameRewatchCursor(remote, localSnapshot) ||
        checkpoint.sessionId != localSnapshot.sessionId) return null
    val oldProof = old.cleanBySeries[key(localSnapshot.canonicalSeriesId)]
    if (oldProof != null &&
        (checkpoint.revision < oldProof.revision ||
            oldProof.simklAccountId != simklAccountId ||
            oldProof.deviceId != deviceId)) return null
    return old.copy(cleanBySeries = old.cleanBySeries + (
        key(localSnapshot.canonicalSeriesId) to RewatchLocalCleanProof(
            simklAccountId, deviceId, checkpoint.revision,
            old.generation, localSnapshot
        )
    ))
}

/**
 * All inputs are checked again under one DataStore edit. A stale network
 * response is never allowed to overwrite a local player write, including
 * when the cursor moved away and later returned to identical values (ABA).
 */
internal fun mergeRewatchRemoteWithLedger(
    sessions: List<RewatchSession>,
    ledger: RewatchLocalSyncLedger,
    profileId: Int,
    simklAccountId: String,
    deviceId: String,
    expectedLocal: RewatchSession?,
    expectedGeneration: Long,
    canonicalSeriesId: String,
    remoteLookup: RewatchRemoteLookup,
    pendingOrUnpublished: Boolean,
    appliedAtEpochMs: Long
): Pair<List<RewatchSession>, RewatchLocalSyncLedger>? {
    if (profileId <= 0 || canonicalSeriesId.isBlank() ||
        simklAccountId.isBlank() || deviceId.isBlank() ||
        pendingOrUnpublished || expectedGeneration != ledger.generation ||
        appliedAtEpochMs < 0L || ledger.generation >= Long.MAX_VALUE - 1) return null
    val verified = remoteLookup as? RewatchRemoteLookup.Verified ?: return null
    val checkpoint = verified.checkpoint as? RewatchRemoteCheckpoint.Synced ?: return null
    val remote = verified.remoteSession ?: return null
    if (remote.ownerKey != "local-profile:$profileId" ||
        key(remote.canonicalSeriesId) != key(canonicalSeriesId) ||
        checkpoint.sessionId != remote.sessionId ||
        remote.updatedAtEpochMs != 0L) return null

    val matches = sessions.withIndex().filter {
        key(it.value.canonicalSeriesId) == key(canonicalSeriesId)
    }
    if (matches.size > 1) return null
    val current = matches.singleOrNull()?.value
    if (current != expectedLocal) return null

    val proof = ledger.cleanBySeries[key(canonicalSeriesId)]
    if (current == null) {
        // A truly empty profile can adopt a verified remote replay. Do not
        // resurrect a replay which had a clean marker but was locally deleted.
        if (proof != null) return null
    } else {
        if (proof == null ||
            proof.simklAccountId != simklAccountId ||
            proof.deviceId != deviceId ||
            proof.generation != ledger.generation ||
            !sameRewatchCursor(proof.cursor, current) ||
            checkpoint.revision <= proof.revision) return null
    }

    val applied = remote.copy(
        updatedAtEpochMs = maxOf(appliedAtEpochMs, current?.updatedAtEpochMs ?: 0L)
    )
    val updatedSessions = if (current == null) sessions + applied
        else sessions.toMutableList().apply {
            set(matches.single().index, applied)
        }
    val generation = ledger.generation + 1L
    val clean = RewatchLocalCleanProof(
        simklAccountId, deviceId, checkpoint.revision, generation, applied
    )
    return updatedSessions to ledger.copy(
        generation = generation,
        cleanBySeries = ledger.cleanBySeries + (key(canonicalSeriesId) to clean)
    )
}
