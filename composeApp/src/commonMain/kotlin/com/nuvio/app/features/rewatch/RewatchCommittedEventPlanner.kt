package com.nuvio.app.features.rewatch

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * An event may be planned only AFTER the new replay snapshot has been
 * durably saved, never from a player callback before persistence.
 *
 * The caller must establish the server checkpoint by an authenticated
 * account-scoped GET; "not queried yet" is NOT the same as "empty".
 *
 * This class is pure. It neither calls Simkl nor touches Home/CW or the VPS.
 */
internal sealed interface RewatchRemoteCheckpoint {
    data object Unknown : RewatchRemoteCheckpoint
    data object ConfirmedEmpty : RewatchRemoteCheckpoint
    data class Synced(val revision: Long, val sessionId: String) : RewatchRemoteCheckpoint {
        init {
            require(revision > 0L && sessionId.isNotBlank())
        }
    }
}

private fun RewatchEpisode.json() = buildJsonObject {
    put("season", season)
    put("episode", episode)
}

private fun RewatchSession.remoteJson() = buildJsonObject {
    // Critical: never send the device-local ownerKey or profile ID.
    put("sessionId", sessionId)
    put("canonicalSeriesId", canonicalSeriesId)
    put("episode", episode.json())
    put("positionMs", positionMs)
    put("durationMs", durationMs)
    if (lastCompletedEpisode == null) {
        put("lastCompletedEpisode", kotlinx.serialization.json.JsonNull)
    } else {
        put("lastCompletedEpisode", lastCompletedEpisode.json())
    }
    put("phase", phase.name)
}

private fun laterEpisode(previous: RewatchEpisode, next: RewatchEpisode): Boolean =
    (next.season == previous.season && next.episode > previous.episode) ||
        (next.season > previous.season && next.episode == 1)

private fun safeRewatchSession(s: RewatchSession): Boolean =
    s.ownerKey.isNotBlank() && s.sessionId.isNotBlank() && s.sessionId.length <= 160 &&
        s.canonicalSeriesId.isNotBlank() && s.canonicalSeriesId.length <= 160 &&
        s.positionMs >= 0 && s.durationMs >= 0 &&
        (s.phase != RewatchPhase.NEXT_UP ||
            (s.lastCompletedEpisode != null &&
                laterEpisode(s.lastCompletedEpisode, s.episode) &&
                s.positionMs == 0L && s.durationMs == 0L)) &&
        (s.phase != RewatchPhase.AWAITING_METADATA ||
            (s.lastCompletedEpisode == s.episode &&
                s.positionMs == 0L && s.durationMs == 0L))

/**
 * Build the exact server event from a committed local transition.
 * Returns null for unknown checkpoints, unsupported transitions,
 * suspicious autoplay callbacks, or mixed local-profile identities.
 *
 * A null result is NOT a signal to drop the locally saved rewatch.
 */
internal fun planCommittedRewatchEvent(
    before: RewatchSession?,
    after: RewatchSession,
    checkpoint: RewatchRemoteCheckpoint,
    eventId: String,
    deviceId: String
): RewatchQueuedEvent? {
    if (!safeRewatchSession(after) ||
        eventId.isBlank() || eventId.length > 160 ||
        deviceId.isBlank() || deviceId.length > 160) return null

    if (checkpoint == RewatchRemoteCheckpoint.Unknown) return null

    val expectedRevision = when (checkpoint) {
        RewatchRemoteCheckpoint.Unknown -> return null
        RewatchRemoteCheckpoint.ConfirmedEmpty -> 0L
        is RewatchRemoteCheckpoint.Synced -> checkpoint.revision
    }

    val type: String
    var startOrImport: RewatchSession? = null
    var episode: RewatchEpisode? = null
    var completedEpisode: RewatchEpisode? = null
    var resultPhase: String? = null
    var nextEpisode: RewatchEpisode? = null
    var position: Long? = null
    var duration: Long? = null

    if (checkpoint == RewatchRemoteCheckpoint.ConfirmedEmpty) {
        // A client must first prove the remote account/series slot is empty.
        // IMPORT can preserve a rewatch already at S3E18 on an in-place upgrade.
        type = "IMPORT_SNAPSHOT"
        startOrImport = after
        when (after.phase) {
            RewatchPhase.PLAYING -> {
                if (after.lastCompletedEpisode != null &&
                    !laterEpisode(after.lastCompletedEpisode, after.episode)) return null
            }
            RewatchPhase.NEXT_UP, RewatchPhase.AWAITING_METADATA -> Unit
            RewatchPhase.FINISHED -> Unit
        }
    } else {
        checkpoint as RewatchRemoteCheckpoint.Synced
        if (before == null || before.sessionId != checkpoint.sessionId ||
            !before.canonicalSeriesId.trim().equals(
                after.canonicalSeriesId.trim(), ignoreCase = true
            ) || before.ownerKey != after.ownerKey) return null

        if (before.sessionId != after.sessionId) {
            if (before.phase != RewatchPhase.FINISHED ||
                after.phase != RewatchPhase.PLAYING ||
                after.positionMs != 0L || after.durationMs != 0L ||
                after.lastCompletedEpisode != null) return null
            type = "START"
            startOrImport = after
        } else {
            if (before == after) return null
            when {
                // A manually selected, already-watched episode can move
                // forward OR backward within the same authenticated session.
                // Autoplay's NEXT_UP transition is handled by COMPLETE_EPISODE,
                // and cannot masquerade as this zero-position PLAYING move.
                before.phase in setOf(
                    RewatchPhase.PLAYING,
                    RewatchPhase.NEXT_UP,
                    RewatchPhase.AWAITING_METADATA
                ) &&
                    before.episode != after.episode &&
                    after.phase == RewatchPhase.PLAYING &&
                    after.positionMs == 0L && after.durationMs == 0L &&
                    after.lastCompletedEpisode == null -> {
                    type = "REPOSITION"
                    episode = after.episode
                }
                before.phase == RewatchPhase.AWAITING_METADATA &&
                    after.lastCompletedEpisode == before.lastCompletedEpisode &&
                    after.lastCompletedEpisode == before.episode &&
                    after.phase in setOf(RewatchPhase.NEXT_UP, RewatchPhase.FINISHED) &&
                    (after.phase != RewatchPhase.NEXT_UP ||
                        laterEpisode(before.episode, after.episode)) -> {
                    type = "RESOLVE_METADATA"
                    completedEpisode = before.episode
                    resultPhase = after.phase.name
                    if (after.phase == RewatchPhase.NEXT_UP) nextEpisode = after.episode
                }
                before.phase == RewatchPhase.PLAYING &&
                    after.lastCompletedEpisode == before.episode &&
                    after.phase in setOf(
                        RewatchPhase.NEXT_UP, RewatchPhase.AWAITING_METADATA,
                        RewatchPhase.FINISHED
                    ) &&
                    (after.phase != RewatchPhase.NEXT_UP ||
                        laterEpisode(before.episode, after.episode)) -> {
                    type = "COMPLETE_EPISODE"
                    episode = before.episode
                    resultPhase = after.phase.name
                    if (after.phase == RewatchPhase.NEXT_UP) nextEpisode = after.episode
                }
                before.phase != RewatchPhase.FINISHED &&
                    after.phase == RewatchPhase.FINISHED -> {
                    type = "STOP"
                }
                before.phase in setOf(RewatchPhase.NEXT_UP, RewatchPhase.PLAYING) &&
                    after.phase == RewatchPhase.PLAYING &&
                    before.episode == after.episode &&
                    (before.phase != RewatchPhase.NEXT_UP ||
                        after.positionMs >= 1_000L) &&
                    (before.phase != after.phase ||
                        before.positionMs != after.positionMs ||
                        before.durationMs != after.durationMs) -> {
                    type = "PROGRESS"
                    episode = after.episode
                    position = after.positionMs
                    duration = after.durationMs
                }
                else -> return null
            }
        }
    }

    val payload = buildJsonObject {
        put("eventId", eventId)
        put("deviceId", deviceId)
        put("canonicalSeriesId", after.canonicalSeriesId)
        put("sessionId", after.sessionId)
        put("expectedRevision", expectedRevision)
        put("type", type)
        startOrImport?.let { put("session", it.remoteJson()) }
        episode?.let { put("episode", it.json()) }
        completedEpisode?.let { put("completedEpisode", it.json()) }
        resultPhase?.let { put("resultPhase", it) }
        nextEpisode?.let { put("nextEpisode", it.json()) }
        position?.let { put("positionMs", it) }
        duration?.let { put("durationMs", it) }
    }.toString()

    if (payload.length > 16_384) return null
    return RewatchQueuedEvent(
        eventId = eventId,
        deviceId = deviceId,
        sessionId = after.sessionId,
        canonicalSeriesId = after.canonicalSeriesId,
        expectedRevision = expectedRevision,
        payload = payload
    )
}
