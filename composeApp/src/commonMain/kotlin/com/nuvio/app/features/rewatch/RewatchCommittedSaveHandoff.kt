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
    val sink: RewatchCommittedSaveSink = DisabledRewatchCommittedSaveSink
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
