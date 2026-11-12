package com.nuvio.app.features.rewatch

import kotlinx.coroutines.withTimeoutOrNull

internal actual object RewatchPlatformSyncLifecycle {
    actual suspend fun homeForeground() {
        if (!RewatchMobileSyncGate.ENABLED) return
        if (!IosRewatchSyncRuntime.activateAfterValidatedRollout()) return
        // Home's own coroutine runs independently of the UI data/load gate.
        IosRewatchSyncRuntime.reconcileForeground()
    }

    actual suspend fun beforePlayback(canonicalSeriesId: String) {
        if (!RewatchMobileSyncGate.ENABLED || canonicalSeriesId.isBlank()) return
        if (!IosRewatchSyncRuntime.activateAfterValidatedRollout()) return
        // Never hold up initial playback indefinitely for optional VPS sync.
        // A timeout leaves independent local replay state intact.
        withTimeoutOrNull(750L) {
            IosRewatchSyncRuntime.reconcileForeground(canonicalSeriesId)
        }
    }
}
