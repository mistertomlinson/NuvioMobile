package com.nuvio.app.features.rewatch

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

private const val MAX_SERVER_INTEGER = 9_007_199_254_740_991L
private val passiveQueueJson = Json

private fun laterPassiveEpisode(previous: RewatchEpisode, next: RewatchEpisode) =
    next.season == previous.season && next.episode > previous.episode ||
        next.season > previous.season && next.episode == 1

/** Receipt validation also covers completions; the persisted field name stays compatible. */
internal fun validArchivedPlaybackCursor(cursor: RewatchCleanCursor): Boolean = runCatching {
    if ((cursor.completedSeason == null) != (cursor.completedEpisode == null)) return false
    val s = cursor.toSession()
    when (s.phase) {
        RewatchPhase.PLAYING -> s.lastCompletedEpisode == null ||
            laterPassiveEpisode(s.lastCompletedEpisode, s.episode)
        RewatchPhase.NEXT_UP -> s.positionMs == 0L && s.durationMs == 0L &&
            s.lastCompletedEpisode != null && laterPassiveEpisode(s.lastCompletedEpisode, s.episode)
        RewatchPhase.AWAITING_METADATA, RewatchPhase.FINISHED ->
            s.positionMs == 0L && s.durationMs == 0L && s.lastCompletedEpisode == s.episode
    }
}.getOrDefault(false)

/**
 * Replay only automatic playback events using the VPS's transition rules.
 * A strict match to the final local cursor proves that archiving this chain
 * does not hide another unqueued selection, completion or position change.
 * REPOSITION, STOP, START and IMPORT are intentionally not inferred here.
 */
internal fun passiveQueueExplainsLocalCursor(
    chain: List<RewatchQueuedEvent>,
    local: RewatchSession
): Boolean = runCatching {
    if (chain.isEmpty() || !validArchivedPlaybackCursor(RewatchCleanCursor.of(local, 1)))
        return false
    var projected: RewatchSession? = null
    for (event in chain) {
        val body = passiveQueueJson.parseToJsonElement(event.payload) as? JsonObject
            ?: return false
        fun text(name: String): String? = (body[name] as? JsonPrimitive)
            ?.takeIf { it.isString }?.content
        fun number(name: String): Long? = (body[name] as? JsonPrimitive)
            ?.takeIf { !it.isString }?.longOrNull
            ?.takeIf { it in 0..MAX_SERVER_INTEGER }
        fun episode(name: String): RewatchEpisode? {
            val value = body[name] as? JsonObject ?: return null
            if (value.keys != setOf("season", "episode")) return null
            fun positive(name: String) = (value[name] as? JsonPrimitive)
                ?.takeIf { !it.isString }?.intOrNull?.takeIf { it > 0 }
            return RewatchEpisode(positive("season") ?: return null,
                positive("episode") ?: return null)
        }
        if (text("eventId") != event.eventId || text("deviceId") != event.deviceId ||
            text("canonicalSeriesId") != event.canonicalSeriesId ||
            text("sessionId") != event.sessionId ||
            event.sessionId != local.sessionId ||
            !event.canonicalSeriesId.trim().equals(local.canonicalSeriesId.trim(), true) ||
            number("expectedRevision") != event.expectedRevision) return false
        val common = setOf("eventId", "deviceId", "canonicalSeriesId", "sessionId", "expectedRevision", "type")
        when (text("type")) {
            "PROGRESS" -> {
                if (body.keys != common + setOf("episode", "positionMs", "durationMs")) return false
                val ep = episode("episode") ?: return false
                val position = number("positionMs") ?: return false
                val duration = number("durationMs") ?: return false
                val before = projected ?: local.copy(episode = ep, phase = RewatchPhase.PLAYING)
                if (before.episode != ep ||
                    before.phase !in setOf(RewatchPhase.PLAYING, RewatchPhase.NEXT_UP) ||
                    before.phase == RewatchPhase.NEXT_UP && position < 1000L) return false
                projected = before.copy(phase = RewatchPhase.PLAYING,
                    positionMs = position, durationMs = duration)
            }
            "COMPLETE_EPISODE", "RESOLVE_METADATA" -> {
                val resolving = text("type") == "RESOLVE_METADATA"
                val episodeField = if (resolving) "completedEpisode" else "episode"
                val completed = episode(episodeField) ?: return false
                val result = text("resultPhase") ?: return false
                val phase = when (result) {
                    "NEXT_UP" -> RewatchPhase.NEXT_UP
                    "AWAITING_METADATA" -> if (!resolving) RewatchPhase.AWAITING_METADATA else return false
                    "FINISHED" -> RewatchPhase.FINISHED
                    else -> return false
                }
                val allowed = common + setOf(episodeField, "resultPhase") +
                    if (phase == RewatchPhase.NEXT_UP) setOf("nextEpisode") else emptySet()
                if (body.keys != allowed) return false
                val next = if (phase == RewatchPhase.NEXT_UP) {
                    (episode("nextEpisode") ?: return false).also {
                        if (!laterPassiveEpisode(completed, it)) return false
                    }
                } else completed
                val before = projected ?: local.copy(episode = completed,
                    phase = if (resolving) RewatchPhase.AWAITING_METADATA else RewatchPhase.PLAYING,
                    lastCompletedEpisode = if (resolving) completed else local.lastCompletedEpisode)
                if (resolving) {
                    if (before.phase != RewatchPhase.AWAITING_METADATA ||
                        before.lastCompletedEpisode != completed || before.episode != completed) return false
                } else if (before.phase != RewatchPhase.PLAYING || before.episode != completed) return false
                projected = before.copy(episode = next, lastCompletedEpisode = completed,
                    phase = phase, positionMs = 0L, durationMs = 0L)
            }
            else -> return false
        }
    }
    projected?.let { RewatchCleanCursor.of(it, 1).matches(local) } == true
}.getOrDefault(false)
