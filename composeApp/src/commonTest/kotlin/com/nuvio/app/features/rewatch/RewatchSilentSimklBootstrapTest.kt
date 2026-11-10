package com.nuvio.app.features.rewatch

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException

class RewatchSilentSimklBootstrapTest {
    private val deviceInstallationId = "f95b5b55-6992-4d5f-b47c-19116c3bc190"
    private val existing = ExistingSimklRewatchAuthorization(
        profileId = 2,
        accessToken = "already-saved-simkl-access-token",
        accountId = 12003
    )
    private val receipt = ReplayBootstrapCredential(
        token = "a".repeat(64),
        deviceId = "simkl-device:8fc3c156-46b0-4cb2-aae7-00498722ccee",
        expiresAtEpochMs = 100_000L
    )

    @Test
    fun inPlaceUpgradeUsesExistingSimklTokenWithoutNewLogin() = runBlocking {
        var current = existing
        var actualToken: String? = null
        var actualInstallationId: String? = null
        var persistedProfile: Int? = null
        val bootstrap = RewatchSilentSimklBootstrap(
            currentAuthorization = { current },
            installationIdForProfile = {
                assertEquals(2, it)
                deviceInstallationId
            },
            exchange = { token, installation ->
                actualToken = token
                actualInstallationId = installation
                receipt
            },
            persist = { profile, _ -> persistedProfile = profile },
            nowEpochMs = { 1000L }
        )
        assertEquals(ReplayBootstrapOutcome.CONNECTED, bootstrap.connectExistingProfile())
        assertEquals(existing.accessToken, actualToken)
        assertEquals(deviceInstallationId, actualInstallationId)
        assertEquals(2, persistedProfile)
        assertEquals(existing, current) // The underlying Simkl login remains untouched.
    }

    @Test
    fun disconnectedSimklSkipsBootstrapWithoutCreatingLogin() = runBlocking {
        var exchangeCount = 0
        val bootstrap = RewatchSilentSimklBootstrap(
            currentAuthorization = { null },
            installationIdForProfile = { deviceInstallationId },
            exchange = { _, _ -> exchangeCount++; receipt },
            persist = { _, _ -> error("No credential may be persisted") },
            nowEpochMs = { 1000L }
        )
        assertEquals(ReplayBootstrapOutcome.NO_EXISTING_SIMKL_LOGIN,
            bootstrap.connectExistingProfile())
        assertEquals(0, exchangeCount)
    }

    @Test
    fun profileSwitchDuringNetworkCallCannotPersistWrongAccount() = runBlocking {
        var current: ExistingSimklRewatchAuthorization? = existing
        var persistCount = 0
        val bootstrap = RewatchSilentSimklBootstrap(
            currentAuthorization = { current },
            installationIdForProfile = { deviceInstallationId },
            exchange = { _, _ ->
                current = existing.copy(profileId = 3, accountId = 99221)
                receipt
            },
            persist = { _, _ -> persistCount++ },
            nowEpochMs = { 1000L }
        )
        assertEquals(ReplayBootstrapOutcome.STALE_PROFILE_OR_AUTHORIZATION,
            bootstrap.connectExistingProfile())
        assertEquals(0, persistCount)
    }

    @Test
    fun tokenRotationDuringNetworkCallCannotBindOldSimklLogin() = runBlocking {
        var current = existing
        val bootstrap = RewatchSilentSimklBootstrap(
            currentAuthorization = { current },
            installationIdForProfile = { deviceInstallationId },
            exchange = { _, _ ->
                current = existing.copy(accessToken = "newly-refreshed-token")
                receipt
            },
            persist = { _, _ -> error("Old receipt must not be saved") },
            nowEpochMs = { 1000L }
        )
        assertEquals(ReplayBootstrapOutcome.STALE_PROFILE_OR_AUTHORIZATION,
            bootstrap.connectExistingProfile())
    }

    @Test
    fun serverUnavailableKeepsExistingRewatchLocalOnly() = runBlocking {
        val bootstrap = RewatchSilentSimklBootstrap(
            currentAuthorization = { existing },
            installationIdForProfile = { deviceInstallationId },
            exchange = { _, _ -> throw IllegalStateException("VPS unavailable") },
            persist = { _, _ -> error("Must not write on network failure") },
            nowEpochMs = { 1000L }
        )
        assertEquals(ReplayBootstrapOutcome.UNAVAILABLE,
            bootstrap.connectExistingProfile())
    }

    @Test
    fun serverCredentialMustBeValidAndNotExpired() = runBlocking {
        for (invalid in listOf(
            receipt.copy(token = "short"),
            receipt.copy(deviceId = "fake"),
            receipt.copy(expiresAtEpochMs = 1000L)
        )) {
            val bootstrap = RewatchSilentSimklBootstrap(
                currentAuthorization = { existing },
                installationIdForProfile = { deviceInstallationId },
                exchange = { _, _ -> invalid },
                persist = { _, _ -> error("Invalid receipt persisted") },
                nowEpochMs = { 1000L }
            )
            assertEquals(ReplayBootstrapOutcome.INVALID_SERVER_CREDENTIAL,
                bootstrap.connectExistingProfile())
        }
    }

    @Test
    fun invalidLocalInstallationIdNeverContactsServer() = runBlocking {
        assertTrue(isValidRewatchInstallationUuid(deviceInstallationId))
        assertFalse(isValidRewatchInstallationUuid(null))
        assertFalse(isValidRewatchInstallationUuid("fixed-profile-2"))
        assertFalse(isValidRewatchInstallationUuid("00000000-0000-1000-8000-000000000001"))
        val bootstrap = RewatchSilentSimklBootstrap(
            currentAuthorization = { existing },
            installationIdForProfile = { "not-a-uuid" },
            exchange = { _, _ -> error("No server call allowed") },
            persist = { _, _ -> error("No save allowed") },
            nowEpochMs = { 1000L }
        )
        assertEquals(ReplayBootstrapOutcome.INVALID_LOCAL_DEVICE_ID,
            bootstrap.connectExistingProfile())
    }

    @Test
    fun cancellationPropagatesWithoutWritingCredential() = runBlocking {
        val bootstrap = RewatchSilentSimklBootstrap(
            currentAuthorization = { existing },
            installationIdForProfile = { deviceInstallationId },
            exchange = { _, _ -> throw CancellationException("profile closed") },
            persist = { _, _ -> error("Cancelled bootstrap must not save") },
            nowEpochMs = { 1000L }
        )
        var cancelled = false
        try {
            bootstrap.connectExistingProfile()
        } catch (_: CancellationException) {
            cancelled = true
        }
        assertTrue(cancelled)
    }
}
