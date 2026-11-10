package com.nuvio.app.features.rewatch

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RewatchTransportCoordinatorTest {
    private val installationId = "a08eb58c-55da-4cec-bbed-b22c59d41ab8"
    private val deviceId = "simkl-device:426ba678-ea50-4b7b-8f97-23907c6d8fad"
    private val login = ExistingSimklRewatchAuthorization(2, "existing-simkl-token", 12003L)

    private class MemoryOutboxStorage : RewatchOutboxStorage {
        val state = mutableMapOf<Int, String>()
        override suspend fun read(profileId: Int): String? = state[profileId]
        override suspend fun write(profileId: Int, payload: String) {
            state[profileId] = payload
        }
    }

    private fun event(): RewatchQueuedEvent {
        val body = """
            {"eventId":"event1","deviceId":"$deviceId",
             "canonicalSeriesId":"imdb:tt0386676","sessionId":"office-replay",
             "expectedRevision":0,"type":"START"}
        """.trimIndent()
        return RewatchQueuedEvent(
            eventId = "event1", deviceId = deviceId,
            canonicalSeriesId = "imdb:tt0386676",
            sessionId = "office-replay", expectedRevision = 0,
            payload = body
        )
    }

    @Test
    fun existingSimklProfileInitializesWithoutSecondLoginAndSendsOfflineOutbox() = runBlocking {
        var connected: ExistingSimklRewatchAuthorization? = login
        val storage = MemoryOutboxStorage()
        val queue = RewatchSyncOutbox(storage) { profile, account, actor ->
            connected?.profileId == profile &&
                connected?.accountId?.toString() == account && actor == deviceId
        }
        assertTrue(queue.enqueue(2, "12003", deviceId, event()))
        var bootstraps = 0
        var uploads = 0
        val api = RewatchVpsApi(RewatchHttpExchange { _, url, _, body ->
            when {
                url.endsWith("/v1/simkl/bootstrap") -> {
                    bootstraps++
                    assertTrue(body.contains("existing-simkl-token"))
                    RewatchHttpResponse(201, """
                        {"schemaVersion":1,"simklAccountId":"12003",
                         "token":"${"b".repeat(64)}","deviceId":"$deviceId",
                         "expiresAtEpochMs":1000000}
                    """.trimIndent())
                }
                url.endsWith("/v1/rewatches/events") -> {
                    uploads++
                    RewatchHttpResponse(200, """{"revision":1}""")
                }
                else -> RewatchHttpResponse(200, """{"schemaVersion":1,"items":[]}""")
            }
        })
        val coordinator = RewatchTransportCoordinator(
            currentAuthorization = { connected },
            installationIdForProfile = { installationId },
            clientId = "existing-nuvio-app",
            api = api, outbox = queue, nowEpochMs = { 1000L }
        )
        assertEquals(ReplayBootstrapOutcome.CONNECTED, coordinator.ensureConnected())
        assertEquals(ReplayBootstrapOutcome.CONNECTED, coordinator.ensureConnected())
        assertEquals(1, bootstraps)
        assertEquals(RewatchOutboxDrainResult.DRAINED, coordinator.submitPending())
        assertEquals(1, uploads)
        assertEquals(0, queue.pendingCount(2, "12003", deviceId))
        assertEquals(1, bootstraps)
    }

    @Test
    fun processRestartCanRebootstrapUsingExistingSimklLogin() = runBlocking {
        var bootstraps = 0
        val api = RewatchVpsApi(RewatchHttpExchange { _, _, _, _ ->
            bootstraps++
            RewatchHttpResponse(201, """
                {"schemaVersion":1,"simklAccountId":"12003",
                 "token":"${"b".repeat(64)}","deviceId":"$deviceId",
                 "expiresAtEpochMs":1000000}
            """.trimIndent())
        })
        fun recreate(): RewatchTransportCoordinator {
            val queue = RewatchSyncOutbox(MemoryOutboxStorage()) { _, _, _ -> true }
            return RewatchTransportCoordinator(
                currentAuthorization = { login },
                installationIdForProfile = { installationId },
                clientId = "existing-nuvio-app",
                api = api, outbox = queue, nowEpochMs = { 1000L }
            )
        }
        assertEquals(ReplayBootstrapOutcome.CONNECTED, recreate().ensureConnected())
        assertEquals(ReplayBootstrapOutcome.CONNECTED, recreate().ensureConnected())
        assertEquals(2, bootstraps)
    }

    @Test
    fun accountChangeDuringBootstrapCannotBindReplayTokenToOtherUser() = runBlocking {
        var current = login
        var uploads = 0
        val api = RewatchVpsApi(RewatchHttpExchange { _, _, _, _ ->
            current = login.copy(profileId = 3, accountId = 99221L)
            RewatchHttpResponse(201, """
                {"schemaVersion":1,"simklAccountId":"12003",
                 "token":"${"b".repeat(64)}","deviceId":"$deviceId",
                 "expiresAtEpochMs":1000000}
            """.trimIndent())
        })
        val queue = RewatchSyncOutbox(MemoryOutboxStorage()) { _, _, _ -> true }
        val coordinator = RewatchTransportCoordinator(
            currentAuthorization = { current },
            installationIdForProfile = { installationId },
            clientId = "existing-nuvio-app", api = api,
            outbox = queue, nowEpochMs = { 1000L }
        )
        assertEquals(ReplayBootstrapOutcome.STALE_PROFILE_OR_AUTHORIZATION,
            coordinator.ensureConnected())
        assertEquals(RewatchOutboxDrainResult.UNAVAILABLE, coordinator.submitPending())
        assertEquals(0, uploads)
    }

    @Test
    fun disconnectedAccountDoesNotContactReplayServer() = runBlocking {
        var contacts = 0
        val api = RewatchVpsApi(RewatchHttpExchange { _, _, _, _ ->
            contacts++
            RewatchHttpResponse(503, "")
        })
        val coordinator = RewatchTransportCoordinator(
            currentAuthorization = { null },
            installationIdForProfile = { error("No installation ID needed") },
            clientId = "existing-nuvio-app", api = api,
            outbox = RewatchSyncOutbox(MemoryOutboxStorage()) { _, _, _ -> true },
            nowEpochMs = { 1000L }
        )
        assertEquals(ReplayBootstrapOutcome.NO_EXISTING_SIMKL_LOGIN,
            coordinator.ensureConnected())
        assertEquals(0, contacts)
    }

    @Test
    fun revokedReplayCredentialIsDiscardedAndNextAttemptReverifiesExistingSimklLogin() = runBlocking {
        var bootstraps = 0
        var reads = 0
        val api = RewatchVpsApi(RewatchHttpExchange { _, url, _, _ ->
            when {
                url.endsWith("/v1/simkl/bootstrap") -> {
                    bootstraps++
                    RewatchHttpResponse(201, """
                        {"schemaVersion":1,"simklAccountId":"12003",
                         "token":"${"b".repeat(64)}","deviceId":"$deviceId",
                         "expiresAtEpochMs":1000000}
                    """.trimIndent())
                }
                else -> {
                    reads++
                    RewatchHttpResponse(if (reads == 1) 401 else 200,
                        """{"schemaVersion":1,"items":[]}""")
                }
            }
        })
        val coordinator = RewatchTransportCoordinator(
            currentAuthorization = { login },
            installationIdForProfile = { installationId },
            clientId = "existing-nuvio-app",
            api = api,
            outbox = RewatchSyncOutbox(MemoryOutboxStorage()) { _, _, _ -> true },
            nowEpochMs = { 1000L }
        )
        assertEquals(RewatchVpsReadResult.Unauthorized, coordinator.fetchRemote())
        assertEquals(1, bootstraps)
        assertEquals(ReplayBootstrapOutcome.CONNECTED, coordinator.ensureConnected())
        assertEquals(2, bootstraps)
    }
}
