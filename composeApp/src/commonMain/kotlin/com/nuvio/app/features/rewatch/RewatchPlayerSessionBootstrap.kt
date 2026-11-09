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
    nowEpochMs: () -> Long
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

    // Never replace a running replay merely because someone selected another
    // previously watched episode.
    if (recorded != null && recorded.phase != RewatchPhase.FINISHED) {
        return recorded.takeIf { it.episode == episode }
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
    return started
}
