package com.nuvio.app.features.rewatch

import kotlinx.coroutines.CancellationException

/**
 * Silent upgrade bridge for an already-connected Simkl profile.
 *
 * This deliberately does not open an OAuth browser, reauthorize Simkl,
 * change watched history, or touch Nuvio's existing Simkl storage.
 *
 * The app wiring supplies the current profile-scoped authorization and a
 * persistent per-profile random installation UUID. The remote exchanges that
 * EXISTING Simkl token once for a replay-only credential.
 *
 * Network and persistent storage are injected so this remains disabled
 * until the private VPS is safely exposed and the client is fully tested.
 */
internal data class ExistingSimklRewatchAuthorization(
    val profileId: Int,
    val accessToken: String,
    val accountId: Long?
)

internal data class ReplayBootstrapCredential(
    val token: String,
    val deviceId: String,
    val expiresAtEpochMs: Long
)

internal enum class ReplayBootstrapOutcome {
    NO_EXISTING_SIMKL_LOGIN,
    INVALID_LOCAL_DEVICE_ID,
    UNAVAILABLE,
    STALE_PROFILE_OR_AUTHORIZATION,
    INVALID_SERVER_CREDENTIAL,
    CONNECTED
}

internal fun isValidRewatchInstallationUuid(value: String?): Boolean =
    value != null && Regex(
        "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-4[0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}"
    ).matches(value)

private fun isValidReplayBootstrapCredential(
    credential: ReplayBootstrapCredential,
    nowEpochMs: Long
): Boolean =
    Regex("[a-f0-9]{64}").matches(credential.token) &&
        credential.deviceId.startsWith("simkl-device:") &&
        credential.deviceId.length in 20..160 &&
        credential.expiresAtEpochMs > nowEpochMs

internal class RewatchSilentSimklBootstrap(
    private val currentAuthorization: () -> ExistingSimklRewatchAuthorization?,
    private val installationIdForProfile: (Int) -> String?,
    private val exchange: suspend (
        simklAccessToken: String,
        installationId: String
    ) -> ReplayBootstrapCredential?,
    private val persist: suspend (
        profileId: Int,
        receipt: ReplayBootstrapCredential
    ) -> Unit,
    private val nowEpochMs: () -> Long
) {
    suspend fun connectExistingProfile(): ReplayBootstrapOutcome {
        val initial = currentAuthorization()?.takeIf {
            it.profileId > 0 && it.accessToken.isNotBlank() &&
                (it.accountId == null || it.accountId > 0)
        } ?: return ReplayBootstrapOutcome.NO_EXISTING_SIMKL_LOGIN

        val installationId = installationIdForProfile(initial.profileId)
            ?.takeIf(::isValidRewatchInstallationUuid)
            ?: return ReplayBootstrapOutcome.INVALID_LOCAL_DEVICE_ID

        val receipt = try {
            exchange(initial.accessToken, installationId)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            null
        }

        // A profile switch or Simkl authorization rotation during the
        // request must never write a credential into another local profile.
        if (currentAuthorization() != initial) {
            return ReplayBootstrapOutcome.STALE_PROFILE_OR_AUTHORIZATION
        }
        if (receipt == null) return ReplayBootstrapOutcome.UNAVAILABLE
        if (!isValidReplayBootstrapCredential(receipt, nowEpochMs())) {
            return ReplayBootstrapOutcome.INVALID_SERVER_CREDENTIAL
        }

        persist(initial.profileId, receipt)
        return ReplayBootstrapOutcome.CONNECTED
    }
}
