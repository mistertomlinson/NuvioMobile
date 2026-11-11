package com.nuvio.app.features.rewatch

import kotlinx.coroutines.CancellationException

/**
 * iPad replay-write boundary, called ONLY after successful local persistence.
 * No Simkl watched-history APIs, network requests or account changes.
 */
internal fun interface RewatchCommittedSaveSink {
    suspend fun committed(
        profileId: Int,
        before: RewatchSession?,
        after: RewatchSession
    )
}

internal object RewatchMobileSyncGate {
    const val ENABLED = false
}

internal object DisabledRewatchCommittedSaveSink : RewatchCommittedSaveSink {
    override suspend fun committed(
        profileId: Int, before: RewatchSession?, after: RewatchSession
    ) = Unit
}

/** Deliberately dormant until iPad, Android and VPS end-to-end validation. */
internal object RewatchIpadCommitHandoff {
    private var installed: RewatchCommittedSaveSink? = null
    val sink: RewatchCommittedSaveSink
        get() = if (RewatchMobileSyncGate.ENABLED)
            installed ?: DisabledRewatchCommittedSaveSink
        else DisabledRewatchCommittedSaveSink

    /** Only an explicitly reviewed future build can install a live sink. */
    fun installAfterValidation(candidate: RewatchCommittedSaveSink): Boolean {
        if (!RewatchMobileSyncGate.ENABLED) return false
        installed = candidate
        return true
    }
}

internal suspend fun notifyCommittedRewatchSave(
    sink: RewatchCommittedSaveSink,
    accepted: Boolean,
    profileId: Int,
    activeProfileId: () -> Int,
    before: RewatchSession?,
    after: RewatchSession
) {
    if (!accepted || profileId <= 0 ||
        activeProfileId() != profileId ||
        after.ownerKey != "local-profile:$profileId" ||
        (before != null && (
            before.ownerKey != after.ownerKey ||
            !before.canonicalSeriesId.trim().equals(
                after.canonicalSeriesId.trim(), ignoreCase = true
            )
        ))) return
    try {
        sink.committed(profileId, before, after)
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        // An optional sync error must not turn an already committed replay
        // into an ordinary Simkl history mutation.
    }
}

/** Non-secret binding from a verified Simkl account and existing Nuvio device. */
internal data class RewatchCommitIdentity(
    val profileId: Int,
    val simklAccountId: String,
    val deviceId: String
)

/**
 * No-network iPad bridge: only a committed local write, a matching saved
 * Simkl account/device, and a verified checkpoint (or persisted acknowledgment)
 * may add an offline event. No event is uploaded from this callback.
 */
internal class RewatchCommittedOutboxSink(
    private val currentIdentity: suspend () -> RewatchCommitIdentity?,
    private val checkpoint: (RewatchCommitIdentity, String) -> RewatchRemoteCheckpoint,
    private val outbox: RewatchSyncOutbox,
    private val eventId: () -> String
) : RewatchCommittedSaveSink {
    override suspend fun committed(
        profileId: Int,
        before: RewatchSession?,
        after: RewatchSession
    ) {
        val identity = currentIdentity() ?: return
        if (identity.profileId != profileId) return
        val proof = checkpoint(identity, after.canonicalSeriesId)
        if (currentIdentity() != identity) return
        outbox.enqueueCommittedTransition(
            profileId = profileId,
            simklAccountId = identity.simklAccountId,
            deviceId = identity.deviceId,
            before = before,
            after = after,
            checkpoint = proof,
            eventId = eventId()
        )
    }
}
