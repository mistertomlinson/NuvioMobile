package com.nuvio.app.features.home

import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.player.skip.PlayerNextEpisodeRules
import com.nuvio.app.features.rewatch.RewatchPhase
import com.nuvio.app.features.rewatch.RewatchSession
import com.nuvio.app.features.watchprogress.ContinueWatchingItem
import com.nuvio.app.features.watchprogress.buildContinueWatchingEpisodeSubtitle

/** A replay is deliberately not a Simkl Next Up/Watched history entry. */
internal fun projectHomeRewatchCard(
    replay: RewatchSession,
    metadata: MetaDetails
): ContinueWatchingItem? {
    if (replay.phase != RewatchPhase.NEXT_UP &&
        replay.phase != RewatchPhase.PLAYING
    ) return null
    if (!replay.canonicalSeriesId.trim().equals(metadata.id.trim(), ignoreCase = true)) return null
    if (metadata.type.lowercase() !in setOf("series", "tv", "show", "tvshow")) return null
    val episode = metadata.videos.singleOrNull {
        it.season == replay.episode.season &&
            it.episode == replay.episode.episode
    } ?: return null
    // Never synthesize an episode video ID from stale Simkl history.
    if (episode.id.isBlank() || !episode.available ||
        !PlayerNextEpisodeRules.hasEpisodeAired(episode.released)
    ) return null

    val nextUp = replay.phase == RewatchPhase.NEXT_UP
    val position = if (nextUp) 0L else replay.positionMs.coerceAtLeast(0L)
    val duration = if (nextUp) 0L else replay.durationMs.coerceAtLeast(0L)
    val fraction = if (duration > 0L) {
        (position.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
    } else 0f

    return ContinueWatchingItem(
        parentMetaId = replay.canonicalSeriesId,
        parentMetaType = "series",
        videoId = episode.id,
        title = metadata.name.ifBlank { replay.canonicalSeriesId },
        subtitle = buildContinueWatchingEpisodeSubtitle(
            replay.episode.season, replay.episode.episode, episode.title
        ),
        imageUrl = episode.thumbnail ?: metadata.background ?: metadata.poster,
        logo = metadata.logo,
        poster = metadata.poster,
        background = metadata.background,
        seasonNumber = replay.episode.season,
        episodeNumber = replay.episode.episode,
        episodeTitle = episode.title,
        episodeThumbnail = episode.thumbnail,
        pauseDescription = episode.overview,
        released = episode.released,
        isNextUp = nextUp,
        nextUpSeedSeasonNumber = if (nextUp) replay.lastCompletedEpisode?.season else null,
        nextUpSeedEpisodeNumber = if (nextUp) replay.lastCompletedEpisode?.episode else null,
        resumePositionMs = position,
        resumeProgressFraction = null,
        durationMs = duration,
        progressFraction = fraction,
        // Rewatch does not mean a newly released episode.
        isReleaseAlert = false,
        isNewSeasonRelease = false,
        isRewatch = true
    )
}

/**
 * A durable replay suppresses the normal provider's old series card even
 * when it is finished or awaiting metadata. Never display the wrong episode
 * merely because its replay metadata request failed.
 */
internal fun mergeHomeRewatchCards(
    providerItems: List<ContinueWatchingItem>,
    profileReplaySessions: List<RewatchSession>,
    verifiedReplayCards: List<ContinueWatchingItem>
): List<ContinueWatchingItem> {
    if (profileReplaySessions.isEmpty()) return providerItems

    fun key(id: String): String = id.trim().lowercase()
    val masked = profileReplaySessions.mapTo(mutableSetOf()) {
        key(it.canonicalSeriesId)
    }
    val byId = profileReplaySessions.associateBy {
        key(it.canonicalSeriesId)
    }
    val verifiedById = verifiedReplayCards
        .mapNotNull { item ->
            if (!item.isRewatch) return@mapNotNull null
            val identity = key(item.parentMetaId)
            val current = byId[identity] ?: return@mapNotNull null
            if (current.phase !in setOf(RewatchPhase.PLAYING, RewatchPhase.NEXT_UP) ||
                item.seasonNumber != current.episode.season ||
                item.episodeNumber != current.episode.episode ||
                item.isNextUp != (current.phase == RewatchPhase.NEXT_UP)
            ) return@mapNotNull null

            // Reuse verified artwork/episode metadata, but never reuse the
            // previous replay's position after a late async save.
            val position = if (item.isNextUp) 0L else current.positionMs.coerceAtLeast(0L)
            val duration = if (item.isNextUp) 0L else current.durationMs.coerceAtLeast(0L)
            val fraction = if (duration > 0L) {
                (position.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
            } else 0f
            identity to item.copy(
                resumePositionMs = position,
                durationMs = duration,
                progressFraction = fraction
            )
        }.toMap()
    val inserted = mutableSetOf<String>()
    val merged = mutableListOf<ContinueWatchingItem>()

    for (item in providerItems) {
        val identity = key(item.parentMetaId)
        if (identity !in masked) {
            merged += item
        } else if (inserted.add(identity)) {
            verifiedById[identity]?.let(merged::add)
        }
    }
    for ((identity, item) in verifiedById) {
        if (inserted.add(identity)) merged += item
    }
    return merged
}
