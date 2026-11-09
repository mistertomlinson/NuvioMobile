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


@Serializable
internal data class RewatchSessionsEnvelope(
    val schemaVersion: Int = REWATCH_STORAGE_SCHEMA_VERSION,
    val sessions: List<RewatchSession> = emptyList()
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
        val loaded = decodeRewatchSessionPayload(
            RewatchSessionStorage.loadPayload(profileId)
        )
        // Read failure must never erase the previously saved payload.
        _snapshot.value = ProfileRewatchSnapshot(profileId, loaded)
        loaded
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
        val original = decodeRewatchSessionPayload(
            RewatchSessionStorage.loadPayload(profileId)
        )
        val updated = mergeRewatchSessionUpdate(
            original, session, replaceSession
        ) ?: return@withLock false

        if (ProfileRepository.activeProfileId != profileId) return@withLock false
        RewatchSessionStorage.savePayload(
            profileId,
            rewatchJson.encodeToString(
                RewatchSessionsEnvelope(sessions = updated)
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
            val stored = decodeRewatchSessionPayload(
                RewatchSessionStorage.loadPayload(profileId)
            )
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
                rewatchJson.encodeToString(RewatchSessionsEnvelope(sessions = next))
            )
            _snapshot.value = ProfileRewatchSnapshot(profileId, next)
            true
        }

}

internal fun decodeRewatchSessionPayload(raw: String?): List<RewatchSession> {
    if (raw == null) return emptyList()
    val payload = rewatchJson.decodeFromString<RewatchSessionsEnvelope>(raw)
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
    return payload.sessions
}

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
