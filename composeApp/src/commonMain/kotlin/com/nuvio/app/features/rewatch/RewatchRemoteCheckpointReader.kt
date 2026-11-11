package com.nuvio.app.features.rewatch

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/**
 * Convert a successful, authenticated GET /v1/rewatches into a checkpoint.
 *
 * The caller must already have verified that the replay credential belongs
 * to the currently connected Simkl account and local profile. This reader
 * never trusts user-controlled owner IDs: the local ownerKey is supplied by
 * the caller and is not accepted from the server JSON.
 *
 * Malformed, ambiguous and duplicate server responses are NOT empty lists.
 * They fail closed to avoid importing an existing replay over newer progress.
 */
@Serializable
private data class VpsRewatchList(
    val schemaVersion: Int,
    val items: List<VpsRewatchItem>
)

@Serializable
private data class VpsRewatchItem(
    val revision: Long,
    val session: VpsRewatchSession
)

@Serializable
private data class VpsRewatchEpisode(
    val season: Int,
    val episode: Int
) {
    fun valid(): Boolean = season > 0 && episode > 0
    fun local(): RewatchEpisode = RewatchEpisode(season, episode)
}

@Serializable
private data class VpsRewatchSession(
    val sessionId: String,
    val canonicalSeriesId: String,
    val episode: VpsRewatchEpisode,
    val positionMs: Long,
    val durationMs: Long,
    val lastCompletedEpisode: VpsRewatchEpisode?,
    val phase: String
)

internal sealed interface RewatchRemoteLookup {
    data object Invalid : RewatchRemoteLookup
    data class Verified(
        val checkpoint: RewatchRemoteCheckpoint,
        val remoteSession: RewatchSession?
    ) : RewatchRemoteLookup
}

private fun after(a: VpsRewatchEpisode, b: VpsRewatchEpisode): Boolean =
    (a.season == b.season && a.episode > b.episode) ||
        (a.season > b.season && a.episode == 1)

private fun validRemote(item: VpsRewatchItem): Boolean {
    val s = item.session
    if (item.revision < 1 || s.sessionId.isBlank() ||
        s.sessionId.length > 160 ||
        s.canonicalSeriesId.isBlank() || s.canonicalSeriesId.length > 160 ||
        !s.episode.valid() || s.lastCompletedEpisode?.valid() == false ||
        s.positionMs < 0 || s.durationMs < 0) return false
    return when (s.phase) {
        "PLAYING" ->
            s.lastCompletedEpisode == null ||
                after(s.episode, s.lastCompletedEpisode)
        "NEXT_UP" ->
            s.positionMs == 0L && s.durationMs == 0L &&
                s.lastCompletedEpisode?.let { after(s.episode, it) } == true
        "AWAITING_METADATA" ->
            s.positionMs == 0L && s.durationMs == 0L &&
                s.episode == s.lastCompletedEpisode
        "FINISHED" ->
            s.lastCompletedEpisode == null ||
                s.episode == s.lastCompletedEpisode ||
                after(s.episode, s.lastCompletedEpisode)
        else -> false
    }
}

internal fun readVerifiedRewatchCheckpoint(
    payload: String,
    expectedCanonicalSeriesId: String,
    currentOwnerKey: String
): RewatchRemoteLookup {
    if (expectedCanonicalSeriesId.isBlank() ||
        expectedCanonicalSeriesId.length > 160 ||
        !Regex("local-profile:[1-9][0-9]*").matches(currentOwnerKey) ||
        payload.length > 64 * 1024) return RewatchRemoteLookup.Invalid
    val parsed = runCatching {
        Json { ignoreUnknownKeys = false }
            .decodeFromString<VpsRewatchList>(payload)
    }.getOrNull() ?: return RewatchRemoteLookup.Invalid
    if (parsed.schemaVersion != 1 || parsed.items.size > 1000 ||
        !parsed.items.all(::validRemote)) return RewatchRemoteLookup.Invalid

    fun key(value: String) = value.trim().lowercase()
    // Duplicate server rows, even unrelated ones, indicate corrupted state.
    val keys = parsed.items.map { key(it.session.canonicalSeriesId) }
    if (keys.distinct().size != keys.size) return RewatchRemoteLookup.Invalid

    val selected = parsed.items.firstOrNull {
        key(it.session.canonicalSeriesId) == key(expectedCanonicalSeriesId)
    } ?: return RewatchRemoteLookup.Verified(
        checkpoint = RewatchRemoteCheckpoint.ConfirmedEmpty,
        remoteSession = null
    )
    val s = selected.session
    return RewatchRemoteLookup.Verified(
        checkpoint = RewatchRemoteCheckpoint.Synced(
            selected.revision, s.sessionId
        ),
        remoteSession = RewatchSession(
            ownerKey = currentOwnerKey,
            canonicalSeriesId = s.canonicalSeriesId,
            sessionId = s.sessionId,
            episode = s.episode.local(),
            positionMs = s.positionMs,
            durationMs = s.durationMs,
            lastCompletedEpisode = s.lastCompletedEpisode?.local(),
            phase = RewatchPhase.valueOf(s.phase),
            // Server revisions, not device wall clocks, determine ordering.
            updatedAtEpochMs = 0L
        )
    )
}
