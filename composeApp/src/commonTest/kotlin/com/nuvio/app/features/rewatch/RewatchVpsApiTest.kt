package com.nuvio.app.features.rewatch

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RewatchVpsApiTest {
    private val installationId = "c9823966-63f5-4d4a-b35b-a48d9ef526da"
    private val credential = ReplayBootstrapCredential(
        simklAccountId = "12003",
        token = "b".repeat(64),
        deviceId = "simkl-device:4da37c48-6caf-4f87-8f87-89061db61188",
        expiresAtEpochMs = 800000L
    )
    private fun event(body: String = """
        {"eventId":"e1","deviceId":"simkl-device:4da37c48-6caf-4f87-8f87-89061db61188",
         "canonicalSeriesId":"imdb:tt0386676","sessionId":"session1","expectedRevision":0,
         "type":"START"}
    """.trimIndent()) = RewatchQueuedEvent(
        eventId = "e1", deviceId = credential.deviceId,
        canonicalSeriesId = "imdb:tt0386676",
        sessionId = "session1", expectedRevision = 0,
        payload = body
    )

    @Test
    fun bootstrapReusesSavedSimklBearerAndReturnsVerifiedAccount() = runBlocking {
        var calls = 0
        val api = RewatchVpsApi(engine = RewatchHttpExchange { method, url, headers, body ->
            calls++
            assertEquals("POST", method)
            assertEquals("https://nuviorewatch.duckdns.org/v1/simkl/bootstrap", url)
            assertFalse(headers.containsKey("Authorization"))
            assertTrue(body.contains("existing-simkl-token"))
            assertTrue(body.contains(installationId))
            RewatchHttpResponse(201, """
                {"schemaVersion":1,"simklAccountId":"12003","token":"${"b".repeat(64)}",
                 "deviceId":"${credential.deviceId}","expiresAtEpochMs":800000}
            """.trimIndent())
        })
        val response = api.bootstrap("existing-app-client", "existing-simkl-token", installationId)
        assertIs<RewatchBootstrapResult.Connected>(response)
        assertEquals(credential, response.credential)
        assertEquals(1, calls)
    }

    @Test
    fun bootstrapWithoutValidInstallationIdentityNeverContactsServer() = runBlocking {
        var calls = 0
        val api = RewatchVpsApi(RewatchHttpExchange { _, _, _, _ ->
            calls++; RewatchHttpResponse(201, "{}")
        })
        assertEquals(RewatchBootstrapResult.InvalidResponse,
            api.bootstrap("existing-app-client", "existing-simkl-token", "bad"))
        assertEquals(0, calls)
    }

    @Test
    fun staleOrUnknownSimklAuthorizationCannotBootstrapReplay() = runBlocking {
        val api = RewatchVpsApi(RewatchHttpExchange { _, _, _, _ ->
            RewatchHttpResponse(401, """{"error":"invalid_simkl_token"}""")
        })
        assertEquals(RewatchBootstrapResult.AuthenticationFailed,
            api.bootstrap("existing-app-client", "expired-simkl-token", installationId))
    }

    @Test
    fun unauthorizedReplayNeverErasesLocalOutbox() = runBlocking {
        val api = RewatchVpsApi(RewatchHttpExchange { _, _, headers, _ ->
            assertEquals("Bearer ${credential.token}", headers["Authorization"])
            RewatchHttpResponse(401, "{}")
        })
        assertEquals(RewatchOutboxSubmitResult.Unauthorized, api.submit(credential, event()))
        assertEquals(RewatchVpsReadResult.Unauthorized, api.list(credential))
    }

    @Test
    fun revisionConflictRequiresReconciliationNotEventDeletion() = runBlocking {
        val api = RewatchVpsApi(RewatchHttpExchange { _, _, _, _ ->
            RewatchHttpResponse(409, """{"error":"revision_conflict"}""")
        })
        assertEquals(RewatchOutboxSubmitResult.Conflict, api.submit(credential, event()))
    }

    @Test
    fun authenticatedAcceptedEventRequiresHigherServerRevision() = runBlocking {
        val okay = RewatchVpsApi(RewatchHttpExchange { _, _, _, _ ->
            RewatchHttpResponse(200, """{"schemaVersion":1,"revision":1}""")
        })
        assertEquals(RewatchOutboxSubmitResult.Accepted(1),
            okay.submit(credential, event()))
        val invalid = RewatchVpsApi(RewatchHttpExchange { _, _, _, _ ->
            RewatchHttpResponse(200, """{"schemaVersion":1,"revision":0}""")
        })
        assertEquals(RewatchOutboxSubmitResult.Unavailable,
            invalid.submit(credential, event()))
    }

    @Test
    fun mismatchedDeviceAndMalformedPayloadCannotReachVps() = runBlocking {
        var count = 0
        val api = RewatchVpsApi(RewatchHttpExchange { _, _, _, _ ->
            count++
            RewatchHttpResponse(200, """{"revision":1}""")
        })
        assertEquals(RewatchOutboxSubmitResult.Unauthorized,
            api.submit(credential, event().copy(deviceId = "simkl-device:other")))
        assertEquals(RewatchOutboxSubmitResult.Conflict,
            api.submit(credential, event("{}")))
        assertEquals(0, count)
    }

    @Test
    fun publicPlaceholderAndRedirectsAreNotTreatedAsSuccess() = runBlocking {
        for (status in listOf(301, 302, 503)) {
            val api = RewatchVpsApi(RewatchHttpExchange { _, _, _, _ ->
                RewatchHttpResponse(status, "")
            })
            assertEquals(RewatchBootstrapResult.Unavailable,
                api.bootstrap("existing-app-client", "existing-simkl-token", installationId))
            assertEquals(RewatchOutboxSubmitResult.Unavailable, api.submit(credential, event()))
        }
    }
}
