package com.nuvio.app.features.rewatch

/**
 * Resolves an isolated replay before a player is allowed to send provider
 * tracking writes. The caller supplies the watched flag from the selected
 * tracking provider and the live active profile ID.
 *
 * This has no side effects on historical watched flags, Simkl or Nuvio CW.
 * It is not called by the iOS player until all playback write paths are gated.
 */
internal suspend fun resolveOrStartRewatchForPlayback(
    store: RewatchSessionAccess,
    expectedProfileId: Int,
    activeProfileId: () -> Int,
    ownerKey: String,
    canonicalSeriesId: String,
    episode: RewatchEpisode,
    wasPreviouslyWatched: suspend () -> Boolean,
    createSessionId: () -> String,
    nowEpochMs: () -> Long,
    explicitManualEpisodeSelection: Boolean = false,
    manualResumePositionMs: Long? = null,
    onCommitted: suspend (RewatchSession?, RewatchSession) -> Unit = { _, _ -> }
): RewatchSession? {
    require(expectedProfileId > 0)
    require(ownerKey.isNotBlank())
    require(canonicalSeriesId.isNotBlank())

    if (activeProfileId() != expectedProfileId) return null
    val existing = store.load(expectedProfileId)
    if (activeProfileId() != expectedProfileId) return null

    val recorded = existing.firstOrNull {
        it.ownerKey == ownerKey &&
            it.canonicalSeriesId.trim().equals(
                canonicalSeriesId.trim(),
                ignoreCase = true
            )
    }

    // A changed playback key is not enough to move a running replay:
    // autoplay, player restores and ordinary first watches also change keys.
    // Only a one-shot explicit episode-list click may request reposition.
    if (recorded != null && recorded.phase != RewatchPhase.FINISHED) {
        if (recorded.episode == episode) return recorded
        if (!explicitManualEpisodeSelection) return null
        if (!wasPreviouslyWatched()) return null
        if (activeProfileId() != expectedProfileId) return null
        val now = nowEpochMs()
        if (now <= recorded.updatedAtEpochMs) return null
        val moved = recorded.copy(
            episode = episode,
            phase = RewatchPhase.PLAYING,
            positionMs = manualResumePositionMs?.takeIf { it > 0L } ?: 0L,
            durationMs = 0L,
            lastCompletedEpisode = null,
            updatedAtEpochMs = now
        )
        if (activeProfileId() != expectedProfileId) return null
        if (!store.upsert(expectedProfileId, moved)) return null
        if (activeProfileId() != expectedProfileId) return null
        try {
            onCommitted(recorded, moved)
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (_: Exception) {
            // Preserve an already-durable move if optional upload is offline.
        }
        return moved
    }

    // Unknown or not-yet-loaded provider history is not affirmative evidence
    // for starting a replay; callers must supply an explicit positive flag.
    val watched = wasPreviouslyWatched()
    if (activeProfileId() != expectedProfileId) return null

    if (decideRewatchActivation(
            storedSessions = existing,
            ownerKey = ownerKey,
            canonicalSeriesId = canonicalSeriesId,
            episode = episode,
            wasAlreadyWatched = watched
        ) != RewatchActivationDecision.START_NEW
    ) return null

    val now = nowEpochMs()
    if (recorded != null && now <= recorded.updatedAtEpochMs) return null
    val started = startRewatchSession(
        ownerKey = ownerKey,
        canonicalSeriesId = canonicalSeriesId,
        sessionId = createSessionId(),
        episode = episode,
        wasAlreadyWatched = true,
        updatedAtEpochMs = now
    ) ?: return null

    if (activeProfileId() != expectedProfileId) return null
    val accepted = store.upsert(
        profileId = expectedProfileId,
        session = started,
        replaceSession = recorded?.phase == RewatchPhase.FINISHED
    )
    if (!accepted || activeProfileId() != expectedProfileId) return null
    try {
        onCommitted(recorded, started)
    } catch (error: kotlinx.coroutines.CancellationException) {
        throw error
    } catch (_: Exception) {
        // Optional replay sync must never affect an already durable start.
    }
    return started
}
