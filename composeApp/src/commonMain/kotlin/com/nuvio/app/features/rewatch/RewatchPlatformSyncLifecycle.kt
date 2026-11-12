package com.nuvio.app.features.rewatch

/**
 * Platform boundary for replay-only foreground and pre-play reconciliation.
 * Neither hook can run network work unless a separately reviewed build
 * enables the existing RewatchMobileSyncGate.
 */
internal expect object RewatchPlatformSyncLifecycle {
    suspend fun homeForeground()
    suspend fun beforePlayback(canonicalSeriesId: String)
}
