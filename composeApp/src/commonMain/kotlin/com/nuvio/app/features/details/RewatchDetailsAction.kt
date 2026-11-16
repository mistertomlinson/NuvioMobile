package com.nuvio.app.features.details

import com.nuvio.app.features.rewatch.ProfileRewatchSnapshot
import com.nuvio.app.features.rewatch.RewatchPhase
import com.nuvio.app.features.rewatch.RewatchSession
import com.nuvio.app.features.watching.domain.playLabel
import com.nuvio.app.features.watching.domain.resumeLabel
import com.nuvio.app.features.watching.domain.upNextLabel

/** An active cursor also masks old provider history while its metadata is unavailable. */
internal data class RewatchDetailsAction(
    val session: RewatchSession,
    val label: String,
    val action: SeriesPrimaryAction?
) {
    val playbackBlocked: Boolean get() = action == null
}

private fun detailsRewatchKey(value: String): String {
    val key = value.trim().lowercase()
    val bare = key.removePrefix("imdb:")
    return if (Regex("tt[0-9]{7,9}").matches(bare)) bare else key
}

internal fun MetaDetails.rewatchDetailsAction(
    profileId: Int,
    snapshot: ProfileRewatchSnapshot?,
    todayIsoDate: String
): RewatchDetailsAction? {
    if (profileId <= 0 || snapshot?.profileId != profileId ||
        type.lowercase() !in setOf("series", "tv", "show", "tvshow")) return null
    val ids = setOfNotNull(id, imdbId).map(::detailsRewatchKey).filter { it.isNotBlank() }.toSet()
    val session = snapshot.sessions.filter {
        it.ownerKey == "local-profile:$profileId" && detailsRewatchKey(it.canonicalSeriesId) in ids
    }.singleOrNull() ?: return null
    if (session.phase !in setOf(RewatchPhase.PLAYING, RewatchPhase.NEXT_UP) ||
        session.episode.season <= 0 || session.episode.episode <= 0 ||
        session.positionMs < 0L || session.durationMs < 0L ||
        session.phase == RewatchPhase.NEXT_UP && (session.positionMs != 0L || session.durationMs != 0L)) return null
    val season = session.episode.season
    val number = session.episode.episode
    val label = when {
        session.phase == RewatchPhase.NEXT_UP -> upNextLabel(season, number)
        session.positionMs > 0L -> resumeLabel(season, number)
        else -> playLabel(season, number)
    }
    val video = videos.filter { it.season == season && it.episode == number }.singleOrNull()
        ?.takeIf { it.id.isNotBlank() && it.isReleasedBy(todayIsoDate) }
    val action = video?.let {
        SeriesPrimaryAction(label = label, videoId = it.id,
            seasonNumber = season, episodeNumber = number,
            episodeTitle = it.title, episodeThumbnail = it.thumbnail,
            // Explicit zero for Next Up prevents fallback to provider resume history.
            resumePositionMs = session.positionMs)
    }
    return RewatchDetailsAction(session, label, action)
}

/** Lazy fallback preserves normal and shuffle behavior when no active replay applies. */
internal inline fun selectDetailsPrimaryAction(
    replay: RewatchDetailsAction?,
    fallback: () -> SeriesPrimaryAction?
): SeriesPrimaryAction? = if (replay != null) replay.action else fallback()
