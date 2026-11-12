package com.nuvio.app.features.rewatch

import kotlinx.coroutines.CancellationException

/**
 * Bounded, demand-driven foreground sync. It only handles independent replay
 * cursors, never Simkl watched flags, scrobbles or regular Continue Watching.
 *
 * All remote reads must be authenticated and verified by the caller; the
 * service owns the Simkl principal and revisions. No upload may be planned
 * from a failed GET, an old-profile result, or an uncommitted local snapshot.
 */
internal enum class RewatchForegroundResult {
    COMPLETED, UNAVAILABLE, LOCAL_CONFLICT, PROFILE_CHANGED, TOO_MANY_SERIES
}

internal interface RewatchForegroundAccess {
    suspend fun connect(): RewatchCommitIdentity?
    fun stillCurrent(identity: RewatchCommitIdentity): Boolean
    suspend fun remoteSeriesIds(): List<String>?
    suspend fun observeLocal(profileId: Int): RewatchObservedLocalSync
    suspend fun applyRemote(seriesId: String): Boolean
    suspend fun checkpoint(seriesId: String): RewatchRemoteLookup
    suspend fun hasPending(identity: RewatchCommitIdentity, seriesId: String): Boolean
    suspend fun enqueueImport(
        identity: RewatchCommitIdentity,
        session: RewatchSession
    ): Boolean
    suspend fun upload(): RewatchOutboxDrainResult
}

internal class RewatchForegroundSynchronizer(
    private val access: RewatchForegroundAccess
) {
    companion object {
        // Avoid amplifying a large or corrupt account roster into many GETs.
        private const val MAX_SYNC_SERIES = 40
    }

    suspend fun reconcile(onlySeries: String? = null): RewatchForegroundResult {
        return try {
            reconcileChecked(onlySeries)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // A transport, cache or storage failure must NEVER erase the local
            // replay or promote a guessed empty checkpoint.
            RewatchForegroundResult.UNAVAILABLE
        }
    }

    private suspend fun reconcileChecked(onlySeries: String?): RewatchForegroundResult {
        val expectedSeries = onlySeries?.trim()?.takeIf { it.isNotBlank() }
        if (onlySeries != null && expectedSeries == null) {
            return RewatchForegroundResult.UNAVAILABLE
        }
        val identity = access.connect() ?: return RewatchForegroundResult.UNAVAILABLE
        fun current() = access.stillCurrent(identity)
        if (!current()) return RewatchForegroundResult.PROFILE_CHANGED
        fun matches(series: String): Boolean = expectedSeries == null ||
            series.trim().equals(expectedSeries, ignoreCase = true)
        fun key(series: String) = series.trim().lowercase()

        val remote = access.remoteSeriesIds()
            ?: return RewatchForegroundResult.UNAVAILABLE
        if (!current()) return RewatchForegroundResult.PROFILE_CHANGED
        if (remote.size > MAX_SYNC_SERIES ||
            remote.any { it.isBlank() || it.length > 160 } ||
            remote.map(::key).distinct().size != remote.size) {
            return RewatchForegroundResult.TOO_MANY_SERIES
        }
        // Never apply remote state to an unrelated local profile.
        for (series in remote.filter(::matches)) {
            if (!current()) return RewatchForegroundResult.PROFILE_CHANGED
            if (!access.applyRemote(series)) {
                return if (current()) RewatchForegroundResult.LOCAL_CONFLICT
                else RewatchForegroundResult.PROFILE_CHANGED
            }
        }

        val initial = access.observeLocal(identity.profileId)
        if (!current()) return RewatchForegroundResult.PROFILE_CHANGED
        if (initial.profileId != identity.profileId ||
            initial.sessions.size > MAX_SYNC_SERIES ||
            initial.sessions.any { it.ownerKey != "local-profile:${identity.profileId}" } ||
            initial.sessions.map { key(it.canonicalSeriesId) }.distinct().size != initial.sessions.size) {
            return RewatchForegroundResult.LOCAL_CONFLICT
        }
        val remoteKeys = remote.map(::key).toSet()
        for (local in initial.sessions.filter { matches(it.canonicalSeriesId) }
            .filter { key(it.canonicalSeriesId) !in remoteKeys }) {
            if (!current()) return RewatchForegroundResult.PROFILE_CHANGED
            val series = local.canonicalSeriesId
            // An older offline event is more authoritative than this single
            // foreground snapshot; do not fabricate a second import.
            if (access.hasPending(identity, series)) continue
            val before = access.observeLocal(identity.profileId)
            if (before.profileId != identity.profileId ||
                before.sessions.singleOrNull { key(it.canonicalSeriesId) == key(series) } != local) {
                return RewatchForegroundResult.LOCAL_CONFLICT
            }
            val verified = access.checkpoint(series) as? RewatchRemoteLookup.Verified
                ?: return RewatchForegroundResult.UNAVAILABLE
            if (!current()) return RewatchForegroundResult.PROFILE_CHANGED
            if (verified.checkpoint != RewatchRemoteCheckpoint.ConfirmedEmpty ||
                verified.remoteSession != null) {
                // The other device may have published since our first GET.
                // Never clobber it with an offline import.
                return RewatchForegroundResult.LOCAL_CONFLICT
            }
            val after = access.observeLocal(identity.profileId)
            if (after.profileId != before.profileId ||
                after.generation != before.generation ||
                after.sessions != before.sessions ||
                !current()) {
                return if (current()) RewatchForegroundResult.LOCAL_CONFLICT
                else RewatchForegroundResult.PROFILE_CHANGED
            }
            if (access.hasPending(identity, series)) continue
            if (!access.enqueueImport(identity, local)) {
                return if (current()) RewatchForegroundResult.LOCAL_CONFLICT
                else RewatchForegroundResult.PROFILE_CHANGED
            }
        }
        if (!current()) return RewatchForegroundResult.PROFILE_CHANGED
        return when (access.upload()) {
            RewatchOutboxDrainResult.EMPTY, RewatchOutboxDrainResult.DRAINED ->
                if (current()) RewatchForegroundResult.COMPLETED
                else RewatchForegroundResult.PROFILE_CHANGED
            RewatchOutboxDrainResult.PROFILE_CHANGED ->
                RewatchForegroundResult.PROFILE_CHANGED
            RewatchOutboxDrainResult.CONFLICT ->
                RewatchForegroundResult.LOCAL_CONFLICT
            RewatchOutboxDrainResult.UNAUTHORIZED, RewatchOutboxDrainResult.UNAVAILABLE ->
                RewatchForegroundResult.UNAVAILABLE
        }
    }
}
