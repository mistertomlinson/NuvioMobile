package com.nuvio.app.features.rewatch

/** NuvioMobile's Android flavor does not have the iOS replay transport. */
internal actual object RewatchPlatformSyncLifecycle {
    actual suspend fun homeForeground() = Unit
    actual suspend fun beforePlayback(canonicalSeriesId: String) = Unit
}
