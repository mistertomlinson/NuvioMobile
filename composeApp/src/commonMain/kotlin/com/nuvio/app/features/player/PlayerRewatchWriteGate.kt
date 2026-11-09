package com.nuvio.app.features.player

import com.nuvio.app.features.rewatch.RewatchEpisode
import com.nuvio.app.features.rewatch.RewatchSession

/**
 * Provider progress/scrobble calls MUST pass this gate for the current
 * playback identity. A new stream/episode is PENDING until its watched-history
 * classification and persisted replay lookup have completed.
 */
internal enum class PlayerRewatchWriteMode {
    PENDING,
    NORMAL,
    REPLAY
}

/**
 * A replay remains a replay for its original playback key even after its
 * durable cursor advances to NEXT_UP or FINISHED. It must not suddenly fall
 * back to normal Simkl writes on the final progress callback.
 */
internal fun playerRewatchWriteMode(
    currentKey: PlaybackKey,
    classifiedKey: PlaybackKey?,
    profileId: Int,
    classifiedProfileId: Int?,
    replaySession: RewatchSession?
): PlayerRewatchWriteMode {
    if (classifiedKey != currentKey ||
        classifiedProfileId != profileId
    ) return PlayerRewatchWriteMode.PENDING
    return if (replaySession != null) {
        PlayerRewatchWriteMode.REPLAY
    } else PlayerRewatchWriteMode.NORMAL
}

internal fun playerRewatchEpisode(season: Int?, episode: Int?): RewatchEpisode? =
    if (season != null && episode != null && season > 0 && episode > 0) {
        RewatchEpisode(season, episode)
    } else null


/** Carries the expected replay identity across the automatic episode switch. */
internal data class PlayerRewatchAutoplayHandoff(
    val sessionId: String,
    val expectedEpisode: RewatchEpisode
)

internal fun playerRewatchAutoplayHandoffReady(
    handoff: PlayerRewatchAutoplayHandoff,
    resolved: RewatchSession?,
    requested: RewatchEpisode?
): Boolean =
    resolved?.sessionId == handoff.sessionId &&
        resolved.episode == handoff.expectedEpisode &&
        requested == handoff.expectedEpisode &&
        resolved.phase in setOf(
            com.nuvio.app.features.rewatch.RewatchPhase.NEXT_UP,
            com.nuvio.app.features.rewatch.RewatchPhase.PLAYING
        )
