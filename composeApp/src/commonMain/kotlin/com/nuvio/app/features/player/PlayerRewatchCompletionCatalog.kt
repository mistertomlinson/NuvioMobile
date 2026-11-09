package com.nuvio.app.features.player

import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.player.skip.PlayerNextEpisodeRules
import com.nuvio.app.features.rewatch.RewatchEpisode

/** An absent/partial episode catalog is never proof that a replay has ended. */
internal data class PlayerRewatchCompletionCatalog(
    val orderedEpisodes: List<RewatchEpisode>?,
    val metadataComplete: Boolean
)

internal fun verifiedPlayerRewatchCompletionCatalog(
    meta: MetaDetails?,
    completed: RewatchEpisode
): PlayerRewatchCompletionCatalog {
    val waiting = PlayerRewatchCompletionCatalog(null, false)
    if (meta == null) return waiting
    val sorted = meta.videos.filter {
        (it.season ?: 0) > 0 && (it.episode ?: 0) > 0
    }.sortedWith(compareBy<MetaVideo> { it.season }.thenBy { it.episode })
    if (sorted.isEmpty()) return waiting
    val identities = sorted.map { RewatchEpisode(it.season!!, it.episode!!) }
    if (identities.size != identities.distinct().size) return waiting
    val currentIndex = identities.indexOf(completed)
    if (currentIndex < 0) return waiting

    val next = sorted.getOrNull(currentIndex + 1)
    if (next != null) {
        if (!next.available ||
            !PlayerNextEpisodeRules.hasEpisodeAired(next.released)
        ) return waiting
        return PlayerRewatchCompletionCatalog(
            orderedEpisodes = listOf(completed, identities[currentIndex + 1]),
            metadataComplete = true
        )
    }
    if (meta.status?.trim()?.lowercase() !in
        setOf("ended", "canceled", "cancelled")
    ) return waiting

    val contiguous = identities.groupBy { it.season }.values.all { episodes ->
        val numbers = episodes.map { it.episode }.sorted()
        numbers == (1..numbers.size).toList()
    }
    if (!contiguous) return waiting

    return PlayerRewatchCompletionCatalog(identities, true)
}
