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
import kotlinx.serialization.json.longOrNull

/**
 * Durable transport-agnostic replay write queue. No Simkl watched-history
 * calls and no writes to the local Continue Watching cache.
 *
 * The caller MUST persist each accepted state transition via [RewatchOutboxStorage]
 * BEFORE attempting network delivery. Events are acknowledged or preserved
 * verbatim in the recovery archive during validated supersession. A 409 alone
 * never authorizes discarding or rebasing offline changes.
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
    val recoveryArchive: List<RewatchQueuedEvent> = emptyList(),
    // Crash-safe receipt for automatic playback events archived after a newer
    // authenticated cursor superseded them. Keep this serialized field name.
    // Local generation is checked atomically before applying that handoff.
    val archivedProgress: Map<String, RewatchArchivedProgressProof> = emptyMap()
)

@Serializable
internal data class RewatchArchivedProgressProof(
    val simklAccountId: String,
    val deviceId: String,
    val generation: Long,
    val cursor: RewatchCleanCursor
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
     * The stored checkpoint is an acknowledged or authenticated server revision.
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
        require(value.archivedProgress.all { (key, proof) ->
            proof.simklAccountId == accountId && proof.deviceId == deviceId &&
                proof.generation >= 0L && proof.generation < Long.MAX_VALUE &&
                key == seriesKey(proof.cursor.canonicalSeriesId) &&
                proof.cursor.ownerKey == "local-profile:$profileId" &&
                proof.cursor.revision > 0L && proof.cursor.revision < Long.MAX_VALUE &&
                proof.cursor.sessionId.isNotBlank() &&
                proof.cursor.season > 0 && proof.cursor.episode > 0 &&
                proof.cursor.positionMs >= 0L && proof.cursor.durationMs >= 0L &&
                validArchivedPlaybackCursor(proof.cursor)
        }) { "Corrupted archived replay progress proof" }
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
            old.copy(pending = old.pending + planned,
                archivedProgress = old.archivedProgress - key)
        ))
        true
    }

    /**
     * A newer authenticated cursor can supersede obsolete automatic playback
     * events, including completion and metadata resolution. Replay the entire
     * chain and match its final cursor before preserving it in the archive.
     * Explicit selections and unexplained changes stay pending. The receipt
     * survives a crash between this write and the atomic local application.
     */
    suspend fun archiveSupersededPlayback(
        identity: RewatchCommitIdentity,
        local: RewatchSession,
        generation: Long,
        lookup: RewatchRemoteLookup,
        localUnchanged: suspend () -> Boolean,
        diagnostic: (String) -> Unit = {}
    ): Boolean = mutex.withLock {
        fun decline(reason: String): Boolean {
            runCatching { diagnostic(reason) }
            return false
        }
        val (profileId, accountId, deviceId) = identity
        if (!stillCurrent(profileId, accountId, deviceId) || !localUnchanged() ||
            local.ownerKey != "local-profile:$profileId" || generation < 0L ||
            generation >= Long.MAX_VALUE - 1L) return@withLock decline("IDENTITY_OR_LOCAL_CHANGED")
        val verified = lookup as? RewatchRemoteLookup.Verified ?: return@withLock decline("UNVERIFIED_REMOTE")
        val checkpoint = verified.checkpoint as? RewatchRemoteCheckpoint.Synced
            ?: return@withLock decline("NO_SYNCED_CHECKPOINT")
        val remote = verified.remoteSession ?: return@withLock decline("NO_REMOTE_CURSOR")
        val key = seriesKey(local.canonicalSeriesId)
        if (remote.ownerKey != local.ownerKey ||
            seriesKey(remote.canonicalSeriesId) != key ||
            checkpoint.sessionId != remote.sessionId ||
            remote.sessionId != local.sessionId || remote.updatedAtEpochMs != 0L ||
            checkpoint.revision < 1L || checkpoint.revision >= Long.MAX_VALUE ||
            (remote.episode == local.episode && local.phase == RewatchPhase.PLAYING &&
                !RewatchCleanCursor.of(local, checkpoint.revision).matches(remote)))
            return@withLock decline("REMOTE_BINDING_OR_EPISODE_CONFLICT")
        val old = readChecked(profileId, accountId, deviceId)
        val chain = old.pending.filter { seriesKey(it.canonicalSeriesId) == key }
        if (chain.isEmpty() || seriesKey(old.pending.first().canonicalSeriesId) != key ||
            old.recoveryArchive.size + chain.size > 1000) return@withLock decline("QUEUE_HEAD_OR_ARCHIVE_CAPACITY")
        val firstRevision = chain.first().expectedRevision
        if (firstRevision < 1L || firstRevision >= checkpoint.revision)
            return@withLock decline("SERVER_REVISION_NOT_NEWER")
        val ack = old.acknowledged[key]
        if (ack != null && (ack.sessionId != local.sessionId ||
                ack.revision > firstRevision)) return@withLock decline("ACKNOWLEDGED_ANCHOR_CONFLICT")
        if (chain.withIndex().any { (index, event) ->
                event.sessionId != local.sessionId || event.deviceId != deviceId ||
                    event.expectedRevision != firstRevision + index.toLong() ||
                    event.expectedRevision >= Long.MAX_VALUE
            }) return@withLock decline("NONCONTIGUOUS_QUEUE")
        if (!passiveQueueExplainsLocalCursor(chain, local))
            return@withLock decline("QUEUE_DOES_NOT_EXPLAIN_LOCAL")
        if (!stillCurrent(profileId, accountId, deviceId) || !localUnchanged())
            return@withLock decline("LOCAL_CHANGED_DURING_RECOVERY")
        storage.write(profileId, json.encodeToString(old.copy(
            pending = old.pending.filterNot { seriesKey(it.canonicalSeriesId) == key },
            recoveryArchive = old.recoveryArchive + chain,
            archivedProgress = old.archivedProgress + (key to RewatchArchivedProgressProof(
                accountId, deviceId, generation,
                RewatchCleanCursor.of(local, checkpoint.revision)
            ))
        )))
        true
    }

    suspend fun archivedProgressProof(
        identity: RewatchCommitIdentity, canonicalSeriesId: String
    ): RewatchArchivedProgressProof? = mutex.withLock {
        val (profileId, accountId, deviceId) = identity
        if (!stillCurrent(profileId, accountId, deviceId)) return@withLock null
        val old = readChecked(profileId, accountId, deviceId)
        val key = seriesKey(canonicalSeriesId)
        if (old.pending.any { seriesKey(it.canonicalSeriesId) == key }) return@withLock null
        old.archivedProgress[key]
    }

    /** Non-secret diagnostics: never expose event payloads or identity values. */
    suspend fun pendingSummary(identity: RewatchCommitIdentity): String = mutex.withLock {
        val (profileId, accountId, deviceId) = identity
        if (!stillCurrent(profileId, accountId, deviceId)) return@withLock "profile_changed=true"
        val pending = readChecked(profileId, accountId, deviceId).pending
        val head = pending.firstOrNull() ?: return@withLock "pending_count=0"
        val type = runCatching { json.parseToJsonElement(head.payload).jsonObject["type"]
            ?.jsonPrimitive?.content }.getOrNull()?.takeIf { it in setOf(
                "PROGRESS", "REPOSITION", "COMPLETE_EPISODE", "RESOLVE_METADATA", "STOP", "IMPORT_SNAPSHOT"
            ) } ?: "UNKNOWN"
        "pending_count=${pending.size} head_type=$type head_revision=${head.expectedRevision}"
    }


    /**
     * Publish or recover a replay move only after a *new*, verified
     * manual episode-picker selection. Never infer manual intent from the
     * rejected event itself, another device, autoplay, or a guessed server
     * revision. The caller must have just observed an empty queue or a verified
     * HTTP 409 (INVALID_PROGRESS or CONFLICT), then fetched current VPS state.
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
        // A fresh manual action may also have missed enqueueing while the
        // authenticated checkpoint was loading. Never bypass another show's
        // blocked queue. Every superseded event remains in the archive.
        if ((old.pending.isNotEmpty() && old.pending.firstOrNull()?.let {
                seriesKey(it.canonicalSeriesId)
            } != key) ||
            old.recoveryArchive.size + pending.size > 1000 ||
            old.recoveryArchive.any {
                it.eventId == repositionEventId || it.eventId == progressEventId
            } || old.pending.any {
                it.eventId == repositionEventId || it.eventId == progressEventId
            }) return@withLock false

        // Validated progress, manual moves, and replay completions can be
        // superseded by this NEW, watched-verified manual action. Validate
        // payload identities as well as envelopes before archiving anything.
        fun payload(event: RewatchQueuedEvent) = runCatching {
            json.parseToJsonElement(event.payload).jsonObject
        }.getOrNull()
        fun validRecoverableEvent(event: RewatchQueuedEvent): Boolean = runCatching {
            val body = payload(event) ?: return@runCatching false
            fun literal(name: String) = body[name]?.jsonPrimitive?.content
            val type = literal("type")
            fun validEpisode(name: String): Boolean {
                val ep = body[name]?.jsonObject ?: return false
                return (ep["season"]?.jsonPrimitive?.intOrNull ?: 0) > 0 &&
                    (ep["episode"]?.jsonPrimitive?.intOrNull ?: 0) > 0
            }
            fun validSuccessor(previousName: String): Boolean {
                if (!validEpisode(previousName) || !validEpisode("nextEpisode")) return false
                val previous = body.getValue(previousName).jsonObject
                val next = body.getValue("nextEpisode").jsonObject
                val season = previous.getValue("season").jsonPrimitive.intOrNull!!
                val number = previous.getValue("episode").jsonPrimitive.intOrNull!!
                val nextSeason = next.getValue("season").jsonPrimitive.intOrNull!!
                val nextNumber = next.getValue("episode").jsonPrimitive.intOrNull!!
                return nextSeason == season && nextNumber > number ||
                    nextSeason > season && nextNumber == 1
            }
            val position = body["positionMs"]?.jsonPrimitive?.longOrNull
            val duration = body["durationMs"]?.jsonPrimitive?.longOrNull
            literal("eventId") == event.eventId &&
                literal("deviceId") == event.deviceId &&
                literal("canonicalSeriesId") == event.canonicalSeriesId &&
                literal("sessionId") == event.sessionId &&
                body["expectedRevision"]?.jsonPrimitive?.longOrNull == event.expectedRevision &&
                when (type) {
                    "PROGRESS" -> validEpisode("episode") && position != null && position >= 0L &&
                        duration != null && duration >= 0L
                    "REPOSITION" ->
                        validEpisode("episode") &&
                        (body["positionMs"] == null || position != null && position >= 0L) &&
                        (body["durationMs"] == null || duration != null && duration >= 0L) &&
                        (duration == null || duration == 0L || (position ?: 0L) < duration) &&
                        listOf("nextEpisode", "completedEpisode", "session").none { it in body }
                    "COMPLETE_EPISODE" -> validEpisode("episode") &&
                        when (literal("resultPhase")) {
                            "NEXT_UP" -> validSuccessor("episode")
                            "AWAITING_METADATA", "FINISHED" -> "nextEpisode" !in body
                            else -> false
                        }
                    "RESOLVE_METADATA" -> validEpisode("completedEpisode") &&
                        when (literal("resultPhase")) {
                            "NEXT_UP" -> validSuccessor("completedEpisode")
                            "FINISHED" -> "nextEpisode" !in body
                            else -> false
                        }
                    else -> false
                }
        }.getOrDefault(false)

        // The rejected client queue may be anchored to an older VPS
        // revision because another device legitimately advanced the same
        // session. Verify the ORIGINAL contiguous local chain separately,
        // then base the replacement event on the latest authenticated GET.
        // A future-revision queue must never be silently discarded.
        val queuedRevision = pending.firstOrNull()?.expectedRevision ?: checkpoint.revision
        if (queuedRevision < 1L || queuedRevision > checkpoint.revision ||
            pending.withIndex().any { (index, event) ->
                event.sessionId != checkpoint.sessionId ||
                    event.deviceId != deviceId ||
                    event.expectedRevision != queuedRevision + index.toLong() ||
                    !validRecoverableEvent(event)
            }) return@withLock false
        val ack = old.acknowledged[key]
        if (ack != null && (ack.sessionId != checkpoint.sessionId ||
            ack.revision > checkpoint.revision ||
            ack.revision > queuedRevision)) return@withLock false

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

        val replacement = listOfNotNull(reposition, progress)
        if (old.pending.size - pending.size + replacement.size > 1000)
            return@withLock false

        if (!stillCurrent(profileId, simklAccountId, deviceId) ||
            !localUnchanged()) return@withLock false
        storage.write(profileId, json.encodeToString(old.copy(
            pending = old.pending.filterNot {
                seriesKey(it.canonicalSeriesId) == key
            } + replacement,
            recoveryArchive = old.recoveryArchive + pending,
            // A fetched revision is now the anchor for the replacement
            // queue. Persist it with the replacement so a concurrent/new
            // progress save can append at revision 667 before upload starts.
            acknowledged = old.acknowledged + (key to RewatchAcknowledgedCheckpoint(
                checkpoint.revision, checkpoint.sessionId
            )),
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
                archivedProgress = envelope.archivedProgress - key,
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
