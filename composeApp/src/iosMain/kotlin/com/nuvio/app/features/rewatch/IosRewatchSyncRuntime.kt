package com.nuvio.app.features.rewatch

import com.nuvio.app.features.simkl.SimklConfig
import com.nuvio.app.features.simkl.SimklPlatformClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import platform.Foundation.NSUUID

/**
 * iPad-only demand-driven replay synchronization composition.
 * Does NOT install on startup. The compile-time rollout gate remains OFF.
 *
 * Uses the already saved, profile-scoped Simkl authorization; neither
 * the replay token nor the original watched-history cursor is persisted.
 */
internal object IosRewatchSyncRuntime {
    private val mutex = Mutex()
    private val foregroundMutex = Mutex()
    private val uploadMutex = Mutex()
    private val syncScope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Main) }
    private var boundAuth: ExistingSimklRewatchAuthorization? = null
    private var boundIdentity: RewatchCommitIdentity? = null
    private val knownCheckpoints = mutableMapOf<String, RewatchRemoteCheckpoint>()

    private fun key(series: String) = series.trim().lowercase()

    private fun currentIdentity(): RewatchCommitIdentity? {
        val auth = RewatchExistingSimklAuthorization.snapshot() ?: return null
        val identity = boundIdentity ?: return null
        return identity.takeIf {
            boundAuth == auth &&
                auth.accountId?.toString() == it.simklAccountId &&
                auth.profileId == it.profileId
        }
    }

    private fun bind(
        auth: ExistingSimklRewatchAuthorization,
        identity: RewatchCommitIdentity
    ) {
        if (RewatchExistingSimklAuthorization.snapshot() != auth ||
            auth.profileId != identity.profileId ||
            auth.accountId?.toString() != identity.simklAccountId) return
        if (boundAuth != auth || boundIdentity != identity) {
            knownCheckpoints.clear()
        }
        boundAuth = auth
        boundIdentity = identity
    }

    private val outbox by lazy {
        RewatchSyncOutbox(IosRewatchOutboxStorage) { profileId, accountId, deviceId ->
            val identity = currentIdentity()
            identity?.profileId == profileId &&
                identity.simklAccountId == accountId &&
                identity.deviceId == deviceId
        }
    }

    private val transport by lazy {
        RewatchTransportCoordinator(
            currentAuthorization = { RewatchExistingSimklAuthorization.snapshot() },
            installationIdForProfile = { IosRewatchOutboxStorage.installationId(it) },
            clientId = SimklConfig.CLIENT_ID,
            api = RewatchVpsApi(IosRewatchVpsHttpExchange),
            outbox = outbox,
            nowEpochMs = { SimklPlatformClock.nowEpochMs() }
        )
    }

    private suspend fun identityForOfflineSave(): RewatchCommitIdentity? {
        val auth = RewatchExistingSimklAuthorization.snapshot() ?: return null
        val account = auth.accountId?.takeIf { it > 0 }?.toString() ?: return null
        currentIdentity()?.takeIf {
            it.profileId == auth.profileId && it.simklAccountId == account
        }?.let { return it }
        val online = transport.currentVerifiedIdentity()?.takeIf {
            it.profileId == auth.profileId && it.simklAccountId == account
        }
        val identity = online ?: run {
            val stored = outbox.knownDeviceIdForAccount(auth.profileId, account)
                ?: return null
            RewatchCommitIdentity(auth.profileId, account, stored)
        }
        if (RewatchExistingSimklAuthorization.snapshot() != auth) return null
        bind(auth, identity)
        return currentIdentity()
    }

    val committedSaveSink: RewatchCommittedSaveSink by lazy {
        val durableOutboxSink = RewatchCommittedOutboxSink(
            currentIdentity = { identityForOfflineSave() },
            checkpoint = { _, series ->
                knownCheckpoints[key(series)] ?: RewatchRemoteCheckpoint.Unknown
            },
            outbox = outbox,
            eventId = { NSUUID().UUIDString }
        )
        RewatchCommittedSaveSink { profileId, before, after ->
            durableOutboxSink.committed(profileId, before, after)
            if (RewatchMobileSyncGate.ENABLED &&
                after.ownerKey == "local-profile:$profileId") {
                // Never make an outgoing request in a playback EOF/local-save
                // callback. The outbox is persisted first; network work is
                // a separate, cancellable process-lifetime coroutine.
                syncScope.launch {
                    try {
                        val drain = uploadPending()
                        if (drain == RewatchOutboxDrainResult.EMPTY ||
                            drain == RewatchOutboxDrainResult.DRAINED) {
                            reconcileForeground(after.canonicalSeriesId)
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // Preserve offline state; never write Simkl history.
                    }
                }
            }
        }
    }

    /** Returns false until end-to-end tests authorize an explicit rollout. */
    fun activateAfterValidatedRollout(): Boolean {
        if (!RewatchMobileSyncGate.ENABLED) return false
        return RewatchIpadCommitHandoff.installAfterValidation(committedSaveSink)
    }

    private suspend fun ensureOnlineIdentity(): RewatchCommitIdentity? {
        if (transport.ensureConnected() != ReplayBootstrapOutcome.CONNECTED)
            return null
        val auth = RewatchExistingSimklAuthorization.snapshot() ?: return null
        val id = transport.currentVerifiedIdentity() ?: return null
        bind(auth, id)
        return currentIdentity()
    }

    /**
     * No automatic polling. Call only from an explicitly enabled future
     * refresh hook. Every incoming update compares the local mutation
     * generation under RewatchSessionRepository's existing mutex.
     */
    suspend fun pullSeries(series: String): Boolean = mutex.withLock {
        if (!RewatchMobileSyncGate.ENABLED || series.isBlank()) return@withLock false
        try {
            val identity = ensureOnlineIdentity() ?: return@withLock false
            fun current() = currentIdentity() == identity
            val observed = RewatchSessionRepository.observeSync(identity.profileId)
            if (!current()) return@withLock false
            val result = transport.fetchCheckpointForSeries(series)
            if (!current()) return@withLock false
            val verified = result as? RewatchRemoteLookup.Verified
                ?: return@withLock false
            knownCheckpoints[key(series)] = verified.checkpoint
            val remote = verified.remoteSession ?: return@withLock false
            if (verified.checkpoint !is RewatchRemoteCheckpoint.Synced)
                return@withLock false
            val pending = outbox.hasPendingForSeries(
                identity.profileId, identity.simklAccountId,
                identity.deviceId, series
            )
            if (pending || !current()) return@withLock false
            val local = observed.sessions.singleOrNull {
                it.canonicalSeriesId.trim().equals(series.trim(), ignoreCase = true)
            }
            val applied = if (local != null && sameRewatchCursor(local, remote)) {
                RewatchSessionRepository.certifyAuthenticatedRemoteMatch(
                    profileId = identity.profileId,
                    simklAccountId = identity.simklAccountId,
                    deviceId = identity.deviceId,
                    expectedLocal = local,
                    expectedGeneration = observed.generation,
                    remote = verified,
                    pendingOrUnpublished = false,
                    stillAuthenticated = ::current
                )
            } else {
                RewatchSessionRepository.applyAuthenticatedRemoteIfClean(
                    profileId = identity.profileId,
                    simklAccountId = identity.simklAccountId,
                    deviceId = identity.deviceId,
                    canonicalSeriesId = series,
                    observed = observed,
                    remote = verified,
                    pendingOrUnpublished = false,
                    nowEpochMs = SimklPlatformClock.nowEpochMs(),
                    stillAuthenticated = ::current
                )
            }
            if (!applied || !current()) return@withLock false
            val persisted = RewatchSessionRepository.observeSync(identity.profileId)
                .sessions.singleOrNull {
                    it.canonicalSeriesId.trim().equals(series.trim(), ignoreCase = true)
                }
            if (persisted != null && sameRewatchCursor(persisted, remote) && current()) {
                outbox.recordVerifiedCleanBaseline(
                    identity.profileId, identity.simklAccountId,
                    identity.deviceId, persisted, verified
                )
            } else false
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            false
        }
    }

    suspend fun uploadPending(): RewatchOutboxDrainResult = uploadMutex.withLock {
        if (!RewatchMobileSyncGate.ENABLED) return@withLock RewatchOutboxDrainResult.UNAVAILABLE
        val identity = ensureOnlineIdentity() ?: return@withLock RewatchOutboxDrainResult.UNAVAILABLE
        if (currentIdentity() != identity) return@withLock RewatchOutboxDrainResult.PROFILE_CHANGED
        val touched = outbox.pendingSeriesIds(
            identity.profileId, identity.simklAccountId, identity.deviceId
        )
        val result = transport.submitPending()
        if (result == RewatchOutboxDrainResult.DRAINED &&
            currentIdentity() == identity) {
            for (series in touched) {
                if (currentIdentity() != identity) break
                try {
                    pullSeries(series)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    // An acknowledged event remains durable. A future
                    // authenticated GET can establish its clean baseline.
                }
            }
        }
        return@withLock result
    }
    private val foregroundSynchronizer by lazy {
        RewatchForegroundSynchronizer(object : RewatchForegroundAccess {
            override suspend fun connect(): RewatchCommitIdentity? = ensureOnlineIdentity()

            override fun stillCurrent(identity: RewatchCommitIdentity): Boolean =
                currentIdentity() == identity

            override suspend fun remoteSeriesIds(): List<String>? =
                transport.fetchVerifiedRemoteSeriesIds()

            override suspend fun observeLocal(profileId: Int): RewatchObservedLocalSync =
                RewatchSessionRepository.observeSync(profileId)

            override suspend fun applyRemote(seriesId: String): Boolean =
                pullSeries(seriesId)

            override suspend fun checkpoint(seriesId: String): RewatchRemoteLookup {
                val identity = currentIdentity() ?: return RewatchRemoteLookup.Invalid
                val proof = transport.fetchCheckpointForSeries(seriesId)
                if (currentIdentity() == identity && proof is RewatchRemoteLookup.Verified) {
                    knownCheckpoints[key(seriesId)] = proof.checkpoint
                }
                return if (currentIdentity() == identity) proof else RewatchRemoteLookup.Invalid
            }

            override suspend fun hasPending(
                identity: RewatchCommitIdentity, seriesId: String
            ): Boolean = outbox.hasPendingForSeries(
                identity.profileId, identity.simklAccountId, identity.deviceId, seriesId
            )

            override suspend fun enqueueImport(
                identity: RewatchCommitIdentity, session: RewatchSession
            ): Boolean {
                if (currentIdentity() != identity) return false
                return outbox.enqueueCommittedTransition(
                    profileId = identity.profileId,
                    simklAccountId = identity.simklAccountId,
                    deviceId = identity.deviceId,
                    before = null, after = session,
                    checkpoint = RewatchRemoteCheckpoint.ConfirmedEmpty,
                    eventId = NSUUID().UUIDString
                )
            }
            override suspend fun upload(): RewatchOutboxDrainResult = uploadPending()
        })
    }

    /** Explicit foreground or pre-play hook only; no default background polling. */
    suspend fun reconcileForeground(onlySeries: String? = null): RewatchForegroundResult {
        if (!RewatchMobileSyncGate.ENABLED) return RewatchForegroundResult.UNAVAILABLE
        return foregroundMutex.withLock {
            foregroundSynchronizer.reconcile(onlySeries)
        }
    }

}
