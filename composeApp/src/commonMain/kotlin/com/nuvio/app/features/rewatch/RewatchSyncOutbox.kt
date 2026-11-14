package com.nuvio.app.features.rewatch

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * Durable transport-agnostic replay write queue. No Simkl watched-history
 * calls and no writes to the local Continue Watching cache.
 *
 * The caller MUST persist each accepted state transition via [RewatchOutboxStorage]
 * BEFORE attempting network delivery. No event may be removed until acknowledged.
 * This queue deliberately stops at 409 rather than discarding offline changes.
 */
@Serializable
internal data class RewatchQueuedEvent(
    val eventId: String,
    val deviceId: String,
    val canonicalSeriesId: String,
    val sessionId: String,
    val expectedRevision: Long,
    val payload: String
)

@Serializable
internal data class RewatchAcknowledgedCheckpoint(
    val revision: Long,
    val sessionId: String
)

/**
 * The last locally verified replay cursor whose data exactly matched a
 * successful authenticated VPS GET. Acknowledged revisions alone do not
 * establish a clean local state.
 */
@Serializable
internal data class RewatchCleanCursor(
    val ownerKey: String,
    val canonicalSeriesId: String,
    val sessionId: String,
    val season: Int,
    val episode: Int,
    val positionMs: Long,
    val durationMs: Long,
    val completedSeason: Int? = null,
    val completedEpisode: Int? = null,
    val phase: String,
    val revision: Long
) {
    fun matches(session: RewatchSession): Boolean =
        ownerKey == session.ownerKey &&
            canonicalSeriesId.trim().equals(session.canonicalSeriesId.trim(), ignoreCase = true) &&
            sessionId == session.sessionId &&
            season == session.episode.season && episode == session.episode.episode &&
            positionMs == session.positionMs && durationMs == session.durationMs &&
            completedSeason == session.lastCompletedEpisode?.season &&
            completedEpisode == session.lastCompletedEpisode?.episode &&
            phase == session.phase.name

    fun toSession(): RewatchSession = RewatchSession(
        ownerKey = ownerKey,
        canonicalSeriesId = canonicalSeriesId,
        sessionId = sessionId,
        episode = RewatchEpisode(season, episode),
        positionMs = positionMs,
        durationMs = durationMs,
        lastCompletedEpisode = if (completedSeason != null && completedEpisode != null)
            RewatchEpisode(completedSeason, completedEpisode) else null,
        phase = RewatchPhase.valueOf(phase),
        updatedAtEpochMs = 0L
    )

    companion object {
        fun of(session: RewatchSession, revision: Long) = RewatchCleanCursor(
            ownerKey = session.ownerKey,
            canonicalSeriesId = session.canonicalSeriesId,
            sessionId = session.sessionId,
            season = session.episode.season,
            episode = session.episode.episode,
            positionMs = session.positionMs,
            durationMs = session.durationMs,
            completedSeason = session.lastCompletedEpisode?.season,
            completedEpisode = session.lastCompletedEpisode?.episode,
            phase = session.phase.name,
            revision = revision
        )
    }
}

@Serializable
internal data class RewatchOutboxEnvelope(
    val schemaVersion: Int = 1,
    val simklAccountId: String,
    val deviceId: String,
    val pending: List<RewatchQueuedEvent> = emptyList(),
    // Retained even after the last event is acknowledged; survives restart.
    val acknowledged: Map<String, RewatchAcknowledgedCheckpoint> = emptyMap(),
    val clean: Map<String, RewatchCleanCursor> = emptyMap(),
    // Never destroy old offline replay events during an explicit recovery.
    // Superseded events stay available locally for audit or manual restore.
    val recoveryArchive: List<RewatchQueuedEvent> = emptyList()
)

internal interface RewatchOutboxStorage {
    suspend fun read(profileId: Int): String?
    suspend fun write(profileId: Int, payload: String)
}

internal sealed interface RewatchOutboxSubmitResult {
    data class Accepted(val revision: Long) : RewatchOutboxSubmitResult
    data object Conflict : RewatchOutboxSubmitResult
    data object InvalidProgress : RewatchOutboxSubmitResult
    data object Unavailable : RewatchOutboxSubmitResult
    data object Unauthorized : RewatchOutboxSubmitResult
}

internal enum class RewatchOutboxDrainResult {
    EMPTY, DRAINED, CONFLICT, INVALID_PROGRESS, UNAVAILABLE, UNAUTHORIZED, PROFILE_CHANGED
}

internal class RewatchSyncOutbox(
    private val storage: RewatchOutboxStorage,
    private val stillCurrent: (profileId: Int, simklAccountId: String, deviceId: String) -> Boolean
) {
    private val mutex = Mutex()
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }

    private fun seriesKey(value: String): String = value.trim().lowercase()

    /**
     * The stored checkpoint is strictly an acknowledged outbound revision.
     * A stale cached GET cannot roll it backward; a newer GET indicates a
     * competing device and must be reconciled before another local write.
     */
    private fun baseline(
        checkpoint: RewatchRemoteCheckpoint,
        acknowledged: RewatchAcknowledgedCheckpoint?
    ): RewatchRemoteCheckpoint? {
        if (acknowledged == null) return checkpoint
        return when (checkpoint) {
            RewatchRemoteCheckpoint.Unknown ->
                RewatchRemoteCheckpoint.Synced(
                    acknowledged.revision, acknowledged.sessionId
                )
            RewatchRemoteCheckpoint.ConfirmedEmpty -> null
            is RewatchRemoteCheckpoint.Synced ->
                if (checkpoint.revision > acknowledged.revision ||
                    checkpoint.sessionId != acknowledged.sessionId) null
                else RewatchRemoteCheckpoint.Synced(
                    acknowledged.revision, acknowledged.sessionId
                )
        }
    }

    private fun validAccount(id: String): Boolean =
        Regex("[1-9][0-9]{0,15}").matches(id) && id.toLongOrNull() != null

    private fun validEvent(event: RewatchQueuedEvent, deviceId: String): Boolean =
        event.eventId.isNotBlank() && event.eventId.length <= 160 &&
            event.deviceId == deviceId &&
            event.canonicalSeriesId.isNotBlank() && event.canonicalSeriesId.length <= 160 &&
            event.sessionId.isNotBlank() && event.sessionId.length <= 160 &&
            event.expectedRevision >= 0L && event.payload.length <= 16_384

    private suspend fun readChecked(
        profileId: Int, accountId: String, deviceId: String
    ): RewatchOutboxEnvelope {
        require(profileId > 0)
        require(validAccount(accountId) && deviceId.isNotBlank())
        val saved = storage.read(profileId)
        if (saved == null) return RewatchOutboxEnvelope(
            simklAccountId = accountId, deviceId = deviceId
        )
        val value = json.decodeFromString<RewatchOutboxEnvelope>(saved)
        require(value.schemaVersion == 1) { "Unsupported offline replay outbox version" }
        require(value.simklAccountId == accountId && value.deviceId == deviceId) {
            "Refusing cross-account or changed-device replay outbox"
        }
        require(value.pending.all { validEvent(it, deviceId) }) {
            "Corrupted replay outbox"
        }
        require(value.recoveryArchive.size <= 1000 &&
            value.recoveryArchive.all { validEvent(it, deviceId) }) {
            "Corrupted replay recovery archive"
        }
        require(value.pending.map { it.eventId }.distinct().size == value.pending.size) {
            "Duplicate replay event IDs"
        }
        require(value.acknowledged.all { (key, ack) ->
            key.isNotBlank() && key.length <= 160 &&
                key == seriesKey(key) &&
                ack.revision > 0 && ack.revision < Long.MAX_VALUE &&
                ack.sessionId.isNotBlank() && ack.sessionId.length <= 160
        }) { "Corrupted acknowledged replay checkpoint" }
        require(value.clean.all { (key, cursor) ->
            key == seriesKey(key) && key.isNotBlank() &&
                key == seriesKey(cursor.canonicalSeriesId) &&
                cursor.ownerKey == "local-profile:$profileId" &&
                cursor.sessionId.isNotBlank() && cursor.sessionId.length <= 160 &&
                cursor.revision > 0 && cursor.revision < Long.MAX_VALUE &&
                cursor.season > 0 && cursor.episode > 0 &&
                cursor.positionMs >= 0 && cursor.durationMs >= 0 &&
                (cursor.completedSeason == null) == (cursor.completedEpisode == null) &&
                (cursor.completedSeason == null || cursor.completedSeason > 0) &&
                (cursor.completedEpisode == null || cursor.completedEpisode > 0) &&
                RewatchPhase.entries.any { it.name == cursor.phase }
        }) { "Corrupted clean replay baseline" }
        return value
    }

    suspend fun enqueue(
        profileId: Int,
        simklAccountId: String,
        deviceId: String,
        event: RewatchQueuedEvent
    ): Boolean = mutex.withLock {
        if (!stillCurrent(profileId, simklAccountId, deviceId)) return@withLock false
        require(validEvent(event, deviceId))
        val old = readChecked(profileId, simklAccountId, deviceId)
        val existing = old.pending.firstOrNull { it.eventId == event.eventId }
        if (existing != null) return@withLock existing == event
        require(old.pending.size < 1000) { "Offline replay outbox capacity reached" }
        if (!stillCurrent(profileId, simklAccountId, deviceId)) return@withLock false
        storage.write(profileId, json.encodeToString(old.copy(pending = old.pending + event)))
        true
    }

    /**
     * Atomically plan and append a committed replay transition under the
     * same outbox lock. A second offline save must use the revision produced
     * by the first pending event, not the stale authenticated GET revision.
     *
     * No VPS call and no cursor/history mutation occurs here. If pending
     * events for this series do not form a contiguous chain, refuse the write
     * instead of creating an event that would conflict or overwrite progress.
     */
    suspend fun enqueueCommittedTransition(
        profileId: Int,
        simklAccountId: String,
        deviceId: String,
        before: RewatchSession?,
        after: RewatchSession,
        checkpoint: RewatchRemoteCheckpoint,
        eventId: String,
        manualRepositionApproved: Boolean = false
    ): Boolean = mutex.withLock {
        if (!stillCurrent(profileId, simklAccountId, deviceId) ||
            after.ownerKey != "local-profile:$profileId") return@withLock false
        val old = readChecked(profileId, simklAccountId, deviceId)
        if (old.pending.any { it.eventId == eventId }) return@withLock false
        if (old.pending.size >= 1000) return@withLock false
        val key = seriesKey(after.canonicalSeriesId)
        val anchor = baseline(checkpoint, old.acknowledged[key])
            ?: return@withLock false
        val pendingForSeries = old.pending.filter {
            seriesKey(it.canonicalSeriesId) == key
        }
        val effectiveCheckpoint = if (pendingForSeries.isEmpty()) {
            anchor
        } else {
            val first = pendingForSeries.first()
            val startingRevision = when (anchor) {
                RewatchRemoteCheckpoint.Unknown -> return@withLock false
                RewatchRemoteCheckpoint.ConfirmedEmpty -> 0L
                is RewatchRemoteCheckpoint.Synced -> anchor.revision
            }
            if (first.expectedRevision != startingRevision ||
                pendingForSeries.withIndex().any { (index, item) ->
                    item.expectedRevision != startingRevision + index.toLong() ||
                        item.deviceId != deviceId ||
                        item.expectedRevision == Long.MAX_VALUE
                }) return@withLock false
            val last = pendingForSeries.last()
            RewatchRemoteCheckpoint.Synced(
                revision = last.expectedRevision + 1,
                sessionId = last.sessionId
            )
        }
        val planned = planCommittedRewatchEvent(
            before = before,
            after = after,
            checkpoint = effectiveCheckpoint,
            eventId = eventId,
            deviceId = deviceId,
            manualRepositionApproved = manualRepositionApproved
        ) ?: return@withLock false
        if (!stillCurrent(profileId, simklAccountId, deviceId)) return@withLock false
        storage.write(profileId, json.encodeToString(
            old.copy(pending = old.pending + planned)
        ))
        true
    }


    /**
     * Recover a known server-rejected PROGRESS only after a *new*, verified
     * manual episode-picker selection. Never infer manual intent from the
     * rejected event itself, another device, autoplay, or a guessed server
     * revision. The caller must have just observed INVALID_PROGRESS.
     *
     * On a fresh authenticated VPS GET, verify the original replay session
     * and revision. An incompatible queue chain is never dropped: its exact
     * events are moved into the same durable outbox's recoveryArchive in one
     * write, while a new REPOSITION (+ optional latest PROGRESS) is staged.
     *
     * This function performs no network requests or Simkl mutations.
     */
    suspend fun recoverExplicitManualSelection(
        profileId: Int,
        simklAccountId: String,
        deviceId: String,
        manualBefore: RewatchSession,
        manualAfter: RewatchSession,
        latestLocal: RewatchSession,
        lookup: RewatchRemoteLookup,
        repositionEventId: String,
        progressEventId: String,
        localUnchanged: suspend () -> Boolean
    ): Boolean = mutex.withLock {
        if (!stillCurrent(profileId, simklAccountId, deviceId) ||
            !localUnchanged() ||
            manualBefore.ownerKey != "local-profile:$profileId" ||
            manualAfter.ownerKey != manualBefore.ownerKey ||
            latestLocal.ownerKey != manualAfter.ownerKey ||
            manualBefore.sessionId != manualAfter.sessionId ||
            latestLocal.sessionId != manualAfter.sessionId ||
            manualBefore.episode == manualAfter.episode ||
            manualAfter.updatedAtEpochMs <= manualBefore.updatedAtEpochMs ||
            latestLocal.updatedAtEpochMs < manualAfter.updatedAtEpochMs ||
            manualAfter.phase != RewatchPhase.PLAYING ||
            latestLocal.phase != RewatchPhase.PLAYING ||
            manualAfter.durationMs != 0L ||
            manualAfter.lastCompletedEpisode != null ||
            latestLocal.lastCompletedEpisode != null ||
            latestLocal.episode != manualAfter.episode ||
            !seriesKey(manualAfter.canonicalSeriesId)
                .equals(seriesKey(manualBefore.canonicalSeriesId)) ||
            !seriesKey(latestLocal.canonicalSeriesId)
                .equals(seriesKey(manualAfter.canonicalSeriesId)) ||
            repositionEventId.isBlank() || progressEventId.isBlank() ||
            repositionEventId == progressEventId) return@withLock false

        val verified = lookup as? RewatchRemoteLookup.Verified
            ?: return@withLock false
        val checkpoint = verified.checkpoint as? RewatchRemoteCheckpoint.Synced
            ?: return@withLock false
        val remote = verified.remoteSession ?: return@withLock false
        val key = seriesKey(manualAfter.canonicalSeriesId)
        if (remote.ownerKey != manualAfter.ownerKey ||
            seriesKey(remote.canonicalSeriesId) != key ||
            remote.sessionId != checkpoint.sessionId ||
            checkpoint.sessionId != manualAfter.sessionId ||
            remote.episode == manualAfter.episode ||
            remote.phase == RewatchPhase.FINISHED) return@withLock false

        val old = readChecked(profileId, simklAccountId, deviceId)
        val pending = old.pending.filter {
            seriesKey(it.canonicalSeriesId) == key
        }
        if (pending.isEmpty() ||
            old.recoveryArchive.size + pending.size > 1000 ||
            old.recoveryArchive.any {
                it.eventId == repositionEventId || it.eventId == progressEventId
            } || old.pending.any {
                it.eventId == repositionEventId || it.eventId == progressEventId
            }) return@withLock false

        // A known invalid-progress head is the only eligible repair target.
        // The server's original episode MUST differ from that event's
        // episode; other invalid_progress causes are not auto-recoverable.
        fun payload(event: RewatchQueuedEvent) = runCatching {
            json.parseToJsonElement(event.payload).jsonObject
        }.getOrNull()
        val firstPayload = payload(pending.first()) ?: return@withLock false
        val firstEpisode = firstPayload["episode"]?.jsonObject
        if (firstPayload["type"]?.jsonPrimitive?.content != "PROGRESS" ||
            firstEpisode?.get("season")?.jsonPrimitive?.intOrNull == remote.episode.season &&
            firstEpisode?.get("episode")?.jsonPrimitive?.intOrNull == remote.episode.episode) {
            return@withLock false
        }
        if (firstEpisode?.get("season")?.jsonPrimitive?.intOrNull == null ||
            firstEpisode?.get("episode")?.jsonPrimitive?.intOrNull == null)
            return@withLock false

        if (pending.first().expectedRevision != checkpoint.revision ||
            pending.withIndex().any { (index, event) ->
                val type = payload(event)?.get("type")?.jsonPrimitive?.content
                event.sessionId != checkpoint.sessionId ||
                    event.deviceId != deviceId ||
                    event.expectedRevision != checkpoint.revision + index ||
                    type !in setOf("PROGRESS", "REPOSITION")
            }) return@withLock false
        val ack = old.acknowledged[key]
        if (ack != null && (ack.sessionId != checkpoint.sessionId ||
            ack.revision > checkpoint.revision)) return@withLock false

        val reposition = planCommittedRewatchEvent(
            before = remote,
            after = manualAfter,
            checkpoint = checkpoint,
            eventId = repositionEventId,
            deviceId = deviceId,
            manualRepositionApproved = true
        ) ?: return@withLock false

        val progress = planCommittedRewatchEvent(
            before = manualAfter,
            after = latestLocal,
            checkpoint = RewatchRemoteCheckpoint.Synced(
                checkpoint.revision + 1, checkpoint.sessionId
            ),
            eventId = progressEventId,
            deviceId = deviceId
        )?.takeIf {
            payload(it)?.get("type")?.jsonPrimitive?.content == "PROGRESS"
        }
        // If local progress changed after the manual click, its latest
        // position must be carried forward, not lost when archiving.
        if ((manualAfter.positionMs != latestLocal.positionMs ||
            manualAfter.durationMs != latestLocal.durationMs) &&
            progress == null) return@withLock false

        if (!stillCurrent(profileId, simklAccountId, deviceId) ||
            !localUnchanged()) return@withLock false
        storage.write(profileId, json.encodeToString(old.copy(
            pending = old.pending.filterNot {
                seriesKey(it.canonicalSeriesId) == key
            } + listOfNotNull(reposition, progress),
            recoveryArchive = old.recoveryArchive + pending,
            clean = old.clean - key
        )))
        true
    }

    suspend fun drain(
        profileId: Int,
        simklAccountId: String,
        deviceId: String,
        submit: suspend (RewatchQueuedEvent) -> RewatchOutboxSubmitResult
    ): RewatchOutboxDrainResult = mutex.withLock {
        if (!stillCurrent(profileId, simklAccountId, deviceId)) {
            return@withLock RewatchOutboxDrainResult.PROFILE_CHANGED
        }
        var outbox = readChecked(profileId, simklAccountId, deviceId)
        if (outbox.pending.isEmpty()) return@withLock RewatchOutboxDrainResult.EMPTY
        while (outbox.pending.isNotEmpty()) {
            if (!stillCurrent(profileId, simklAccountId, deviceId)) {
                return@withLock RewatchOutboxDrainResult.PROFILE_CHANGED
            }
            val event = outbox.pending.first()
            val result = try {
                submit(event)
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (_: Throwable) {
                RewatchOutboxSubmitResult.Unavailable
            }
            if (!stillCurrent(profileId, simklAccountId, deviceId)) {
                return@withLock RewatchOutboxDrainResult.PROFILE_CHANGED
            }
            when (result) {
                is RewatchOutboxSubmitResult.Accepted -> {
                    require(event.expectedRevision < Long.MAX_VALUE &&
                        result.revision == event.expectedRevision + 1L) {
                        "Server must acknowledge exactly the next replay revision"
                    }
                    val key = seriesKey(event.canonicalSeriesId)
                    val previousAck = outbox.acknowledged[key]
                    require(previousAck == null ||
                        result.revision > previousAck.revision) {
                        "Replay acknowledgment must advance persisted revision"
                    }
                    // Save the acknowledgment atomically with dequeue. If
                    // storage fails, the original event remains retryable.
                    outbox = outbox.copy(
                        pending = outbox.pending.drop(1),
                        acknowledged = outbox.acknowledged + (
                            key to RewatchAcknowledgedCheckpoint(
                                result.revision, event.sessionId
                            )
                        )
                    )
                    storage.write(profileId, json.encodeToString(outbox))
                }
                RewatchOutboxSubmitResult.Conflict ->
                    return@withLock RewatchOutboxDrainResult.CONFLICT
                RewatchOutboxSubmitResult.InvalidProgress ->
                    return@withLock RewatchOutboxDrainResult.INVALID_PROGRESS
                RewatchOutboxSubmitResult.Unauthorized ->
                    return@withLock RewatchOutboxDrainResult.UNAUTHORIZED
                RewatchOutboxSubmitResult.Unavailable ->
                    return@withLock RewatchOutboxDrainResult.UNAVAILABLE
            }
        }
        RewatchOutboxDrainResult.DRAINED
    }

    /**
     * Record a clean cursor ONLY when an authenticated VPS read exactly
     * matches the still-current local replay. Pending replay events prohibit
     * this proof. No clean marker is ever invented from an HTTP failure or a
     * successful POST acknowledgment alone.
     *
     * The caller must recheck the local cursor under its DataStore transaction
     * before any subsequent remote promotion; this method is a marker only.
     */
    suspend fun recordVerifiedCleanBaseline(
        profileId: Int,
        simklAccountId: String,
        deviceId: String,
        local: RewatchSession,
        lookup: RewatchRemoteLookup
    ): Boolean = mutex.withLock {
        if (!stillCurrent(profileId, simklAccountId, deviceId) ||
            local.ownerKey != "local-profile:$profileId") return@withLock false
        val verified = lookup as? RewatchRemoteLookup.Verified ?: return@withLock false
        val remote = verified.remoteSession ?: return@withLock false
        val checkpoint = verified.checkpoint as? RewatchRemoteCheckpoint.Synced
            ?: return@withLock false
        val key = seriesKey(local.canonicalSeriesId)
        if (remote.ownerKey != local.ownerKey ||
            !remote.canonicalSeriesId.trim().equals(
                local.canonicalSeriesId.trim(), ignoreCase = true
            ) ||
            remote.sessionId != checkpoint.sessionId) return@withLock false
        val cursor = RewatchCleanCursor.of(local, checkpoint.revision)
        if (!cursor.matches(remote)) return@withLock false
        val envelope = readChecked(profileId, simklAccountId, deviceId)
        if (envelope.pending.any { seriesKey(it.canonicalSeriesId) == key })
            return@withLock false
        val previous = envelope.clean[key]
        val ack = envelope.acknowledged[key]
        if ((previous != null && checkpoint.revision < previous.revision) ||
            (ack != null && (
                checkpoint.revision < ack.revision ||
                (checkpoint.revision == ack.revision && checkpoint.sessionId != ack.sessionId)
            ))) return@withLock false
        if (!stillCurrent(profileId, simklAccountId, deviceId)) return@withLock false
        // The GET is an independent server confirmation: unlike a POST
        // acknowledgment it may reflect a revision written by another TV
        // or iPad. Keeping it alongside the clean proof allows the next
        // local event to use that server revision after an app restart.
        storage.write(profileId, json.encodeToString(
            envelope.copy(
                clean = envelope.clean + (key to cursor),
                acknowledged = envelope.acknowledged + (
                    key to RewatchAcknowledgedCheckpoint(
                        checkpoint.revision, checkpoint.sessionId
                    )
                )
            )
        ))
        true
    }

    /** Read only a proven cursor; caller still compares its local DataStore
     * value and checks for concurrent unpublished writes before applying. */
    suspend fun cleanBaseline(
        profileId: Int, simklAccountId: String, deviceId: String,
        canonicalSeriesId: String
    ): RewatchCleanCursor? = mutex.withLock {
        if (!stillCurrent(profileId, simklAccountId, deviceId)) return@withLock null
        val key = seriesKey(canonicalSeriesId)
        val envelope = readChecked(profileId, simklAccountId, deviceId)
        if (envelope.pending.any { seriesKey(it.canonicalSeriesId) == key })
            return@withLock null
        envelope.clean[key]
    }

    suspend fun acknowledgedCheckpoint(
        profileId: Int, simklAccountId: String, deviceId: String,
        canonicalSeriesId: String
    ): RewatchAcknowledgedCheckpoint? = mutex.withLock {
        require(canonicalSeriesId.isNotBlank())
        readChecked(profileId, simklAccountId, deviceId)
            .acknowledged[seriesKey(canonicalSeriesId)]
    }

    /**
     * Recover a non-secret device binding after process restart without a
     * network request. This is ONLY usable when the existing saved Simkl
     * login independently confirms the identical account for this profile.
     * A missing, different-account or corrupt outbox never creates an
     * identity and never resets the previous payload.
     */
    suspend fun knownDeviceIdForAccount(
        profileId: Int, simklAccountId: String
    ): String? = mutex.withLock {
        if (profileId <= 0 || !validAccount(simklAccountId)) return@withLock null
        val raw = storage.read(profileId) ?: return@withLock null
        val parsed = runCatching {
            json.decodeFromString<RewatchOutboxEnvelope>(raw)
        }.getOrNull() ?: return@withLock null
        if (parsed.simklAccountId != simklAccountId ||
            parsed.deviceId.isBlank()) return@withLock null
        val verified = runCatching {
            readChecked(profileId, simklAccountId, parsed.deviceId)
        }.getOrNull() ?: return@withLock null
        verified.deviceId
    }

    suspend fun hasPendingForSeries(
        profileId: Int, simklAccountId: String, deviceId: String,
        canonicalSeriesId: String
    ): Boolean = mutex.withLock {
        if (!stillCurrent(profileId, simklAccountId, deviceId)) return@withLock true
        val key = seriesKey(canonicalSeriesId)
        if (key.isBlank()) return@withLock true
        readChecked(profileId, simklAccountId, deviceId)
            .pending.any { seriesKey(it.canonicalSeriesId) == key }
    }

    /** Preserve the touched series roster before a successful drain so a
     * fresh authenticated GET can re-certify each local replay afterward. */
    suspend fun pendingSeriesIds(
        profileId: Int, simklAccountId: String, deviceId: String
    ): Set<String> = mutex.withLock {
        if (!stillCurrent(profileId, simklAccountId, deviceId))
            return@withLock emptySet()
        readChecked(profileId, simklAccountId, deviceId)
            .pending.map { it.canonicalSeriesId.trim().lowercase() }.toSet()
    }

    suspend fun pendingCount(
        profileId: Int, simklAccountId: String, deviceId: String
    ): Int = mutex.withLock {
        readChecked(profileId, simklAccountId, deviceId).pending.size
    }
}
