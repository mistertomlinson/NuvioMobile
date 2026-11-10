package com.nuvio.app.features.rewatch

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * In-memory replay-only session, independent of Simkl history.
 *
 * On process restart the VPS token is intentionally forgotten. The EXISTING
 * saved Simkl login silently bootstraps it again. No extra login or settings.
 * Durable replay events and installation UUID live in a separate per-profile
 * outbox storage.
 *
 * This is an injectable client-side runtime, not hooked to app startup or
 * the player until the transport / merge tests are complete.
 */
internal class RewatchTransportCoordinator(
    private val currentAuthorization: () -> ExistingSimklRewatchAuthorization?,
    private val installationIdForProfile: suspend (profileId: Int) -> String,
    private val clientId: String,
    private val api: RewatchVpsApi,
    private val outbox: RewatchSyncOutbox,
    private val nowEpochMs: () -> Long
) {
    private data class Lease(
        val profileId: Int,
        val simklAccountId: String,
        val credential: ReplayBootstrapCredential,
        val simklToken: String
    )
    private val mutex = Mutex()
    private var currentLease: Lease? = null

    fun reset() {
        currentLease = null
    }

    private fun valid(lease: Lease): Boolean {
        val now = currentAuthorization() ?: return false
        return now.profileId == lease.profileId &&
            now.accessToken == lease.simklToken &&
            (now.accountId == null ||
                now.accountId.toString() == lease.simklAccountId) &&
            lease.credential.expiresAtEpochMs > nowEpochMs() + 60_000L
    }

    suspend fun ensureConnected(): ReplayBootstrapOutcome = mutex.withLock {
        val previous = currentLease
        if (previous != null && valid(previous)) {
            return@withLock ReplayBootstrapOutcome.CONNECTED
        }
        currentLease = null
        val initial = currentAuthorization()?.takeIf {
            it.profileId > 0 && it.accessToken.isNotBlank()
        } ?: return@withLock ReplayBootstrapOutcome.NO_EXISTING_SIMKL_LOGIN

        val installationId = try {
            installationIdForProfile(initial.profileId)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            return@withLock ReplayBootstrapOutcome.INVALID_LOCAL_DEVICE_ID
        }

        var received: ReplayBootstrapCredential? = null
        val bootstrap = RewatchSilentSimklBootstrap(
            currentAuthorization = currentAuthorization,
            installationIdForProfile = { profileId ->
                installationId.takeIf { profileId == initial.profileId }
            },
            exchange = { token, id ->
                when (val result = api.bootstrap(clientId, token, id)) {
                    is RewatchBootstrapResult.Connected -> result.credential
                    else -> null
                }
            },
            persist = { profileId, credential ->
                // This "persist" callback holds the replay token in RAM only.
                // No secondary credential is written to disk.
                if (currentAuthorization() == initial &&
                    profileId == initial.profileId) {
                    received = credential
                }
            },
            nowEpochMs = nowEpochMs
        )
        val result = bootstrap.connectExistingProfile()
        if (result != ReplayBootstrapOutcome.CONNECTED) return@withLock result
        val credential = received ?: return@withLock ReplayBootstrapOutcome.STALE_PROFILE_OR_AUTHORIZATION
        if (currentAuthorization() != initial) {
            return@withLock ReplayBootstrapOutcome.STALE_PROFILE_OR_AUTHORIZATION
        }
        currentLease = Lease(
            profileId = initial.profileId,
            simklAccountId = credential.simklAccountId,
            credential = credential,
            simklToken = initial.accessToken
        )
        result
    }

    suspend fun submitPending(): RewatchOutboxDrainResult {
        val connected = ensureConnected()
        if (connected != ReplayBootstrapOutcome.CONNECTED) {
            return RewatchOutboxDrainResult.UNAVAILABLE
        }
        val lease = currentLease ?: return RewatchOutboxDrainResult.UNAVAILABLE
        if (!valid(lease)) return RewatchOutboxDrainResult.PROFILE_CHANGED
        val result = outbox.drain(
            profileId = lease.profileId,
            simklAccountId = lease.simklAccountId,
            deviceId = lease.credential.deviceId
        ) { event ->
            if (!valid(lease)) RewatchOutboxSubmitResult.Unauthorized
            else api.submit(lease.credential, event)
        }
        if (result == RewatchOutboxDrainResult.UNAUTHORIZED) reset()
        return result
    }

    suspend fun fetchRemote(): RewatchVpsReadResult {
        val connected = ensureConnected()
        if (connected != ReplayBootstrapOutcome.CONNECTED) {
            return RewatchVpsReadResult.Unavailable
        }
        val lease = currentLease ?: return RewatchVpsReadResult.Unavailable
        if (!valid(lease)) return RewatchVpsReadResult.Unauthorized
        val result = api.list(lease.credential)
        if (result == RewatchVpsReadResult.Unauthorized) reset()
        return result
    }
}
