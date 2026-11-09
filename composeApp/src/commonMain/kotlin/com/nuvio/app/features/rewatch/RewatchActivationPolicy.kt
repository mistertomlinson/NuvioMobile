package com.nuvio.app.features.rewatch

/**
 * Rewatch detection must be conservative. A first watch must never be converted
 * into a replay, and an unfinished replay must not be overwritten by playback
 * of another previously watched episode of the same series.
 */
enum class RewatchActivationDecision {
    RESUME_EXISTING,
    START_NEW,
    NORMAL_PLAYBACK
}

fun decideRewatchActivation(
    storedSessions: List<RewatchSession>,
    ownerKey: String,
    canonicalSeriesId: String,
    episode: RewatchEpisode,
    wasAlreadyWatched: Boolean
): RewatchActivationDecision {
    require(ownerKey.isNotBlank())
    require(canonicalSeriesId.isNotBlank())

    val stored = storedSessions.firstOrNull {
        it.ownerKey == ownerKey &&
            it.canonicalSeriesId.trim().equals(
                canonicalSeriesId.trim(), ignoreCase = true
            )
    }

    if (stored != null && stored.phase != RewatchPhase.FINISHED) {
        return if (stored.episode == episode) {
            RewatchActivationDecision.RESUME_EXISTING
        } else {
            // Do not clobber or fork the current series replay just because a
            // user plays an unrelated watched episode.
            RewatchActivationDecision.NORMAL_PLAYBACK
        }
    }

    return if (wasAlreadyWatched) {
        RewatchActivationDecision.START_NEW
    } else {
        RewatchActivationDecision.NORMAL_PLAYBACK
    }
}
