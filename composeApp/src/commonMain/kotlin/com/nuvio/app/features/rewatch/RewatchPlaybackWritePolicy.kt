package com.nuvio.app.features.rewatch

/**
 * A replay has its own progress and completion state: never use ordinary
 * Simkl scrobble/history writes to advance an established replay.
 *
 * The caller resolves profile and content identities before this decision.
 * This policy is not connected to the 0.6.7 player yet.
 */
enum class RewatchPlaybackWriteRoute {
    NORMAL_TRACKING,
    REWATCH_SESSION_ONLY
}

fun resolveRewatchPlaybackWriteRoute(
    session: RewatchSession?,
    ownerKey: String,
    canonicalSeriesId: String,
    episode: RewatchEpisode
): RewatchPlaybackWriteRoute {
    if (session == null || session.phase == RewatchPhase.FINISHED) {
        return RewatchPlaybackWriteRoute.NORMAL_TRACKING
    }
    if (session.ownerKey != ownerKey) {
        return RewatchPlaybackWriteRoute.NORMAL_TRACKING
    }
    if (!session.canonicalSeriesId.trim().equals(
            canonicalSeriesId.trim(), ignoreCase = true
        )
    ) {
        return RewatchPlaybackWriteRoute.NORMAL_TRACKING
    }
    if (session.episode != episode) {
        return RewatchPlaybackWriteRoute.NORMAL_TRACKING
    }
    // Metadata loss cannot downgrade an active replay to a provider write.
    return RewatchPlaybackWriteRoute.REWATCH_SESSION_ONLY
}
