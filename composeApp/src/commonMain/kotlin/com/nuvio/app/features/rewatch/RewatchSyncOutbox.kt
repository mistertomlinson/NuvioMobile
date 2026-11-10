package com.nuvio.app.features.rewatch

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

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
internal data class RewatchOutboxEnvelope(
    val schemaVersion: Int = 1,
    val simklAccountId: String,
    val deviceId: String,
    val pending: List<RewatchQueuedEvent> = emptyList()
)

internal interface RewatchOutboxStorage {
    suspend fun read(profileId: Int): String?
    suspend fun write(profileId: Int, payload: String)
}

internal sealed interface RewatchOutboxSubmitResult {
    data class Accepted(val revision: Long) : RewatchOutboxSubmitResult
    data object Conflict : RewatchOutboxSubmitResult
    data object Unavailable : RewatchOutboxSubmitResult
    data object Unauthorized : RewatchOutboxSubmitResult
}

internal enum class RewatchOutboxDrainResult {
    EMPTY, DRAINED, CONFLICT, UNAVAILABLE, UNAUTHORIZED, PROFILE_CHANGED
}

internal class RewatchSyncOutbox(
    private val storage: RewatchOutboxStorage,
    private val stillCurrent: (profileId: Int, simklAccountId: String, deviceId: String) -> Boolean
) {
    private val mutex = Mutex()
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }

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
        require(value.pending.map { it.eventId }.distinct().size == value.pending.size) {
            "Duplicate replay event IDs"
        }
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
                    require(result.revision > event.expectedRevision) {
                        "Server revision must advance"
                    }
                    outbox = outbox.copy(pending = outbox.pending.drop(1))
                    storage.write(profileId, json.encodeToString(outbox))
                }
                RewatchOutboxSubmitResult.Conflict ->
                    return@withLock RewatchOutboxDrainResult.CONFLICT
                RewatchOutboxSubmitResult.Unauthorized ->
                    return@withLock RewatchOutboxDrainResult.UNAUTHORIZED
                RewatchOutboxSubmitResult.Unavailable ->
                    return@withLock RewatchOutboxDrainResult.UNAVAILABLE
            }
        }
        RewatchOutboxDrainResult.DRAINED
    }

    suspend fun pendingCount(
        profileId: Int, simklAccountId: String, deviceId: String
    ): Int = mutex.withLock {
        readChecked(profileId, simklAccountId, deviceId).pending.size
    }
}
