package com.nuvio.app.features.rewatch

/**
 * Pure reconciliation boundary for a future authenticated VPS transport.
 *
 * A server revision is the only ordering authority across devices. Device
 * wall clocks, local profile numbers, and Simkl watch-history timestamps must
 * not decide which replay wins.
 */
internal data class RemoteRewatchSnapshot(
    val revision: Long,
    val session: RewatchSession
) {
    init {
        require(revision > 0L)
    }
}

internal sealed interface RewatchSyncDecision {
    data object KeepLocal : RewatchSyncDecision
    data class ApplyRemote(val snapshot: RemoteRewatchSnapshot) : RewatchSyncDecision
    data class Conflict(val snapshot: RemoteRewatchSnapshot) : RewatchSyncDecision
}

/**
 * Do not silently overwrite an offline local replay or tombstone.
 * An explicit conflict requires refetch + user-approved or semantically
 * validated reconciliation once the VPS API is implemented.
 */
internal fun reconcileRewatchSnapshot(
    local: RewatchSession?,
    remote: RemoteRewatchSnapshot?,
    lastAppliedServerRevision: Long,
    hasUnacknowledgedLocalChanges: Boolean
): RewatchSyncDecision {
    require(lastAppliedServerRevision >= 0L)
    if (remote == null || remote.revision <= lastAppliedServerRevision) {
        return RewatchSyncDecision.KeepLocal
    }
    if (hasUnacknowledgedLocalChanges) {
        return RewatchSyncDecision.Conflict(remote)
    }
    // A newer server snapshot is authoritative only when there are no
    // unpublished local changes. The caller must also verify the authenticated
    // owner/profile and canonical series identity before invoking this reducer.
    if (local != null && (
        local.ownerKey != remote.session.ownerKey ||
        !local.canonicalSeriesId.trim().equals(
            remote.session.canonicalSeriesId.trim(), ignoreCase = true
        )
    )) {
        return RewatchSyncDecision.Conflict(remote)
    }
    return RewatchSyncDecision.ApplyRemote(remote)
}
