package com.nuvio.app.features.rewatch

/**
 * One-shot, in-memory intent from an actual episode-list click.
 *
 * A changed playback key alone is NOT proof of user intent: autoplay and
 * restored playback also change keys. This marker is never persisted, never
 * sent to Simkl, and cannot migrate between profiles or series.
 */
internal object RewatchManualEpisodeSelection {
    data class Selected(val resumePositionMs: Long?)
    private data class Requested(
        val profileId: Int,
        val canonicalSeriesId: String,
        val episode: RewatchEpisode,
        val createdAtEpochMs: Long,
        val resumePositionMs: Long?
    )

    private var pending: Requested? = null
    private const val MAX_AGE_MS = 120_000L

    fun arm(
        profileId: Int,
        seriesId: String,
        episode: RewatchEpisode,
        nowEpochMs: Long,
        resumePositionMs: Long? = null
    ) {
        if (profileId <= 0 || seriesId.isBlank() || nowEpochMs < 0L) return
        pending = Requested(
            profileId, seriesId.trim().lowercase(), episode, nowEpochMs,
            resumePositionMs?.takeIf { it > 0L }
        )
    }

    fun consume(
        profileId: Int,
        seriesId: String,
        episode: RewatchEpisode,
        nowEpochMs: Long
    ): Selected? {
        val requested = pending ?: return null
        val ageMs = nowEpochMs - requested.createdAtEpochMs
        if (ageMs !in 0L..MAX_AGE_MS) {
            pending = null
            return null
        }
        if (requested.profileId != profileId ||
            requested.canonicalSeriesId != seriesId.trim().lowercase() ||
            requested.episode != episode) return null
        pending = null
        return Selected(requested.resumePositionMs)
    }

    internal fun clearForTests() { pending = null }
}
