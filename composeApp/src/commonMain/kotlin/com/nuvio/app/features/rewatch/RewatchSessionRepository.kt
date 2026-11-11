package com.nuvio.app.features.rewatch

import com.nuvio.app.features.profiles.ProfileRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val REWATCH_STORAGE_SCHEMA_VERSION = 1

/** Tagged identity prevents old-profile replay data from reaching new Home. */
internal data class ProfileRewatchSnapshot(
    val profileId: Int,
    val sessions: List<RewatchSession>
)

/** One consistent local cursor/version snapshot for race-safe remote pulls. */
internal data class RewatchObservedLocalSync(
    val profileId: Int,
    val sessions: List<RewatchSession>,
    val generation: Long
)


@Serializable
internal data class RewatchSessionsEnvelope(
    val schemaVersion: Int = REWATCH_STORAGE_SCHEMA_VERSION,
    val sessions: List<RewatchSession> = emptyList(),
    val syncLedger: RewatchLocalSyncLedger = RewatchLocalSyncLedger()
)

private val rewatchJson = Json {
    ignoreUnknownKeys = false
    encodeDefaults = true
}

/**
 * This is deliberately distinct from Simkl history, watched flags and the
 * existing Nuvio watch-progress payload. Nothing calls it from playback yet.
 */
/** Local-only session storage boundary, injectable in replay activation tests. */
internal interface RewatchSessionAccess {
    suspend fun load(profileId: Int): List<RewatchSession>
    suspend fun upsert(
        profileId: Int,
        session: RewatchSession,
        replaceSession: Boolean = false
    ): Boolean
}

internal object RewatchSessionRepository : RewatchSessionAccess {
    private val mutex = Mutex()
    private val _snapshot = MutableStateFlow<ProfileRewatchSnapshot?>(null)
    val snapshot: StateFlow<ProfileRewatchSnapshot?> = _snapshot

    override suspend fun load(profileId: Int): List<RewatchSession> = mutex.withLock {
        require(profileId > 0)
        val loaded = decodeRewatchSessionEnvelope(
            RewatchSessionStorage.loadPayload(profileId), profileId
        ).sessions
        // Read failure must never erase the previously saved payload.
        _snapshot.value = ProfileRewatchSnapshot(profileId, loaded)
        loaded
    }

    suspend fun observeSync(profileId: Int): RewatchObservedLocalSync = mutex.withLock {
        require(profileId > 0)
        val state = decodeRewatchSessionEnvelope(
            RewatchSessionStorage.loadPayload(profileId), profileId
        )
        RewatchObservedLocalSync(profileId, state.sessions, state.syncLedger.generation)
    }

    suspend fun certifyAuthenticatedRemoteMatch(
        profileId: Int,
        simklAccountId: String,
        deviceId: String,
        expectedLocal: RewatchSession,
        expectedGeneration: Long,
        remote: RewatchRemoteLookup,
        pendingOrUnpublished: Boolean,
        stillAuthenticated: () -> Boolean
    ): Boolean = mutex.withLock {
        if (profileId <= 0 || ProfileRepository.activeProfileId != profileId ||
            !stillAuthenticated()) return@withLock false
        val envelope = decodeRewatchSessionEnvelope(
            RewatchSessionStorage.loadPayload(profileId), profileId
        )
        val updated = certifyRewatchLocalClean(
            envelope.sessions, envelope.syncLedger, profileId,
            simklAccountId, deviceId, expectedLocal,
            expectedGeneration, remote, pendingOrUnpublished
        ) ?: return@withLock false
        if (ProfileRepository.activeProfileId != profileId ||
            !stillAuthenticated()) return@withLock false
        RewatchSessionStorage.savePayload(profileId,
            rewatchJson.encodeToString(envelope.copy(syncLedger = updated)))
        true
    }

    suspend fun applyAuthenticatedRemoteIfClean(
        profileId: Int,
        simklAccountId: String,
        deviceId: String,
        canonicalSeriesId: String,
        observed: RewatchObservedLocalSync,
        remote: RewatchRemoteLookup,
        pendingOrUnpublished: Boolean,
        nowEpochMs: Long,
        stillAuthenticated: () -> Boolean
    ): Boolean = mutex.withLock {
        if (profileId <= 0 || observed.profileId != profileId ||
            ProfileRepository.activeProfileId != profileId ||
            !stillAuthenticated()) return@withLock false
        val envelope = decodeRewatchSessionEnvelope(
            RewatchSessionStorage.loadPayload(profileId), profileId
        )
        val expectedLocal = observed.sessions.firstOrNull {
            it.canonicalSeriesId.trim().equals(
                canonicalSeriesId.trim(), ignoreCase = true
            )
        }
        val updated = mergeRewatchRemoteWithLedger(
            sessions = envelope.sessions,
            ledger = envelope.syncLedger,
            profileId = profileId,
            simklAccountId = simklAccountId,
            deviceId = deviceId,
            expectedLocal = expectedLocal,
            expectedGeneration = observed.generation,
            canonicalSeriesId = canonicalSeriesId,
            remoteLookup = remote,
            pendingOrUnpublished = pendingOrUnpublished,
            appliedAtEpochMs = nowEpochMs
        ) ?: return@withLock false
        if (ProfileRepository.activeProfileId != profileId ||
            !stillAuthenticated()) return@withLock false
        RewatchSessionStorage.savePayload(profileId,
            rewatchJson.encodeToString(envelope.copy(
                sessions = updated.first,
                syncLedger = updated.second
            )))
        _snapshot.value = ProfileRewatchSnapshot(profileId, updated.first)
        true
    }

    /**
     * Explicit profile ID and serialized read/modify/write prevent an event
     * from being accidentally stored under whichever profile later becomes
     * active. A different session requires an intentional restart.
     */
    override suspend fun upsert(
        profileId: Int,
        session: RewatchSession,
        replaceSession: Boolean
    ): Boolean = mutex.withLock {
        require(profileId > 0)
        // Serialize writes and verify the original profile while the lock is
        // held. Otherwise a profile switch could commit an outgoing replay
        // into the wrong currently displayed profile.
        if (ProfileRepository.activeProfileId != profileId) return@withLock false
        val envelope = decodeRewatchSessionEnvelope(
            RewatchSessionStorage.loadPayload(profileId), profileId
        )
        val updated = mergeRewatchSessionUpdate(
            envelope.sessions, session, replaceSession
        ) ?: return@withLock false

        if (ProfileRepository.activeProfileId != profileId) return@withLock false
        RewatchSessionStorage.savePayload(
            profileId,
            rewatchJson.encodeToString(
                envelope.copy(
                    sessions = updated,
                    syncLedger = onLocalRewatchCommitted(envelope.syncLedger, session)
                )
            )
        )
        _snapshot.value = ProfileRewatchSnapshot(profileId, updated)
        true
    }
    /**
     * Dismiss only the independent replay, retaining a FINISHED tombstone so
     * the old provider Next Up cannot reappear after Home recomputes.
     */
    suspend fun dismiss(profileId: Int, ownerKey: String, contentId: String): Boolean =
        mutex.withLock {
            if (profileId <= 0 || ProfileRepository.activeProfileId != profileId) {
                return@withLock false
            }
            val envelope = decodeRewatchSessionEnvelope(
                RewatchSessionStorage.loadPayload(profileId), profileId
            )
            val stored = envelope.sessions
            val index = stored.indexOfFirst {
                it.ownerKey == ownerKey &&
                    it.canonicalSeriesId.trim().equals(contentId.trim(), ignoreCase = true)
            }
            if (index < 0) return@withLock false
            val original = stored[index]
            if (original.phase == RewatchPhase.FINISHED) return@withLock true
            val next = stored.toMutableList()
            next[index] = original.copy(
                phase = RewatchPhase.FINISHED,
                updatedAtEpochMs = maxOf(
                    com.nuvio.app.features.watchprogress.WatchProgressClock.nowEpochMs(),
                    original.updatedAtEpochMs + 1L
                )
            )
            if (ProfileRepository.activeProfileId != profileId) return@withLock false
            RewatchSessionStorage.savePayload(
                profileId,
                rewatchJson.encodeToString(envelope.copy(
                    sessions = next,
                    syncLedger = onLocalRewatchCommitted(
                        envelope.syncLedger, next[index]
                    )
                ))
            )
            _snapshot.value = ProfileRewatchSnapshot(profileId, next)
            true
        }

}

internal fun decodeRewatchSessionEnvelope(
    raw: String?,
    profileId: Int? = null
): RewatchSessionsEnvelope {
    val payload = if (raw == null) RewatchSessionsEnvelope()
        else rewatchJson.decodeFromString<RewatchSessionsEnvelope>(raw)
    require(payload.schemaVersion == REWATCH_STORAGE_SCHEMA_VERSION) {
        "Unsupported rewatch payload version"
    }
    require(payload.sessions.all { session ->
        session.ownerKey.isNotBlank() &&
            session.canonicalSeriesId.isNotBlank() &&
            session.sessionId.isNotBlank() &&
            session.episode.season > 0 &&
            session.episode.episode > 0 &&
            session.updatedAtEpochMs >= 0L
    }) { "Invalid rewatch session payload" }
    require(payload.sessions
        .map { it.ownerKey to it.canonicalSeriesId.trim().lowercase() }
        .distinct().size == payload.sessions.size
    ) { "Duplicate rewatch session identity" }
    if (profileId != null) {
        validateRewatchLocalSyncLedger(payload.syncLedger, profileId)
    } else {
        require(payload.syncLedger.schemaVersion == 1 &&
            payload.syncLedger.generation >= 0)
    }
    return payload
}

internal fun decodeRewatchSessionPayload(raw: String?): List<RewatchSession> =
    decodeRewatchSessionEnvelope(raw).sessions

internal fun mergeRewatchSessionUpdate(
    existing: List<RewatchSession>,
    incoming: RewatchSession,
    replaceSession: Boolean = false
): List<RewatchSession>? {
    require(incoming.ownerKey.isNotBlank())
    require(incoming.canonicalSeriesId.isNotBlank())
    require(incoming.sessionId.isNotBlank())

    val contentKey = incoming.canonicalSeriesId.trim().lowercase()
    val index = existing.indexOfFirst { session ->
        session.canonicalSeriesId.trim().lowercase() == contentKey
    }
    if (index == -1) return existing + incoming

    val previous = existing[index]
    if (previous.ownerKey != incoming.ownerKey) return null
    if (previous.sessionId != incoming.sessionId && !replaceSession) return null
    if (previous.sessionId == incoming.sessionId &&
        incoming.updatedAtEpochMs < previous.updatedAtEpochMs
    ) return null
    return existing.toMutableList().apply { set(index, incoming) }
}
