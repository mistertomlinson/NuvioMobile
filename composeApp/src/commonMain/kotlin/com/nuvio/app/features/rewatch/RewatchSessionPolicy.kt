package com.nuvio.app.features.rewatch

import kotlinx.serialization.Serializable

/**
 * Independent replay cursor. This model never reads or writes tracking-provider
 * watched history. The caller owns persistence, identity resolution and sync.
 */
@Serializable
data class RewatchEpisode(val season: Int, val episode: Int) {
    init {
        require(season > 0) { "Specials are not implicitly part of a series replay" }
        require(episode > 0)
    }
}

@Serializable
enum class RewatchPhase {
    PLAYING,
    NEXT_UP,
    AWAITING_METADATA,
    FINISHED
}

@Serializable
data class RewatchSession(
    val ownerKey: String,
    val canonicalSeriesId: String,
    val sessionId: String,
    val episode: RewatchEpisode,
    val positionMs: Long,
    val durationMs: Long,
    val lastCompletedEpisode: RewatchEpisode?,
    val phase: RewatchPhase,
    val updatedAtEpochMs: Long
)

/**
 * The watched flag is an input from original tracking history, not a mutation.
 * An explicit replay gesture may be supported separately by the UI.
 */
fun startRewatchSession(
    ownerKey: String,
    canonicalSeriesId: String,
    sessionId: String,
    episode: RewatchEpisode,
    wasAlreadyWatched: Boolean,
    updatedAtEpochMs: Long
): RewatchSession? {
    if (!wasAlreadyWatched) return null
    require(ownerKey.isNotBlank())
    require(canonicalSeriesId.isNotBlank())
    require(sessionId.isNotBlank())
    require(updatedAtEpochMs >= 0L)
    return RewatchSession(
        ownerKey = ownerKey,
        canonicalSeriesId = canonicalSeriesId,
        sessionId = sessionId,
        episode = episode,
        positionMs = 0L,
        durationMs = 0L,
        lastCompletedEpisode = null,
        phase = RewatchPhase.PLAYING,
        updatedAtEpochMs = updatedAtEpochMs
    )
}

/**
 * Only progress for this session's selected episode may update its cursor.
 * Provider history is deliberately not an input to this reducer.
 */
fun recordRewatchProgress(
    session: RewatchSession,
    episode: RewatchEpisode,
    positionMs: Long,
    durationMs: Long,
    updatedAtEpochMs: Long
): RewatchSession {
    if (session.phase == RewatchPhase.FINISHED ||
        session.phase == RewatchPhase.AWAITING_METADATA ||
        episode != session.episode ||
        updatedAtEpochMs < session.updatedAtEpochMs
    ) return session

    return session.copy(
        positionMs = positionMs.coerceAtLeast(0L),
        durationMs = durationMs.coerceAtLeast(0L),
        phase = RewatchPhase.PLAYING,
        updatedAtEpochMs = updatedAtEpochMs
    )
}

/**
 * Invoked only after the player has established a genuine episode completion.
 * Metadata must represent the complete, ordered playable episode list to
 * finalize the season or series. An incomplete lookup cannot drop a replay.
 */
fun completeRewatchEpisode(
    session: RewatchSession,
    completedEpisode: RewatchEpisode,
    orderedPlayableEpisodes: List<RewatchEpisode>?,
    metadataComplete: Boolean,
    updatedAtEpochMs: Long
): RewatchSession {
    // A queued Next Up episode must actually start before it may complete.
    // AWAITING_METADATA must resolve its previous completion, not duplicate it.
    if (session.phase != RewatchPhase.PLAYING ||
        completedEpisode != session.episode ||
        updatedAtEpochMs < session.updatedAtEpochMs
    ) return session

    return resolveRewatchSuccessor(
        session = session.copy(
            lastCompletedEpisode = completedEpisode,
            positionMs = 0L,
            durationMs = 0L,
            updatedAtEpochMs = updatedAtEpochMs
        ),
        orderedPlayableEpisodes = orderedPlayableEpisodes,
        metadataComplete = metadataComplete
    )
}

fun resolvePendingRewatchSuccessor(
    session: RewatchSession,
    orderedPlayableEpisodes: List<RewatchEpisode>?,
    metadataComplete: Boolean
): RewatchSession {
    if (session.phase != RewatchPhase.AWAITING_METADATA ||
        session.lastCompletedEpisode == null
    ) return session
    return resolveRewatchSuccessor(session, orderedPlayableEpisodes, metadataComplete)
}

private fun resolveRewatchSuccessor(
    session: RewatchSession,
    orderedPlayableEpisodes: List<RewatchEpisode>?,
    metadataComplete: Boolean
): RewatchSession {
    val completed = session.lastCompletedEpisode ?: return session
    if (!metadataComplete || orderedPlayableEpisodes.isNullOrEmpty()) {
        return session.copy(phase = RewatchPhase.AWAITING_METADATA)
    }

    val completedIndex = orderedPlayableEpisodes.indexOf(completed)
    if (completedIndex < 0 || orderedPlayableEpisodes.distinct().size != orderedPlayableEpisodes.size) {
        return session.copy(phase = RewatchPhase.AWAITING_METADATA)
    }

    val next = orderedPlayableEpisodes.getOrNull(completedIndex + 1)
        ?: return session.copy(phase = RewatchPhase.FINISHED)

    return session.copy(
        episode = next,
        positionMs = 0L,
        durationMs = 0L,
        phase = RewatchPhase.NEXT_UP
    )
}
