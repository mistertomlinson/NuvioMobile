package com.nuvio.app.features.rewatch

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Rewatch-only API client. No Simkl watched-history or scrobble API usage.
 * Never log HTTP bodies: bootstrap carries an existing Simkl bearer token.
 *
 * An app platform supplies a TLS-only transport with redirect following OFF.
 * This class has NO startup wiring and cannot run until explicitly invoked.
 */
internal data class RewatchHttpResponse(val status: Int, val body: String)
internal fun interface RewatchHttpExchange {
    suspend fun request(
        method: String, url: String, headers: Map<String, String>, body: String
    ): RewatchHttpResponse
}

@Serializable
private data class BootstrapRequest(
    val clientId: String,
    val accessToken: String,
    val installationId: String
)

@Serializable
private data class BootstrapResponse(
    val schemaVersion: Int,
    val simklAccountId: String,
    val token: String,
    val deviceId: String,
    val expiresAtEpochMs: Long
)

internal sealed interface RewatchBootstrapResult {
    data class Connected(
        val simklAccountId: String,
        val credential: ReplayBootstrapCredential
    ) : RewatchBootstrapResult
    data object AuthenticationFailed : RewatchBootstrapResult
    data object Unavailable : RewatchBootstrapResult
    data object InvalidResponse : RewatchBootstrapResult
}

internal sealed interface RewatchVpsReadResult {
    data class Received(val json: String) : RewatchVpsReadResult
    data object Unauthorized : RewatchVpsReadResult
    data object Unavailable : RewatchVpsReadResult
}

internal class RewatchVpsApi(
    private val engine: RewatchHttpExchange,
    private val endpoint: String = "https://nuviorewatch.duckdns.org"
) {
    init {
        require(endpoint == "https://nuviorewatch.duckdns.org") {
            "Replay sync must use the approved HTTPS origin"
        }
    }
    private val json = Json { ignoreUnknownKeys = false; encodeDefaults = true }

    private suspend fun call(
        method: String, path: String, token: String?, body: String
    ): RewatchHttpResponse? = try {
        engine.request(
            method = method, url = endpoint + path,
            headers = buildMap {
                put("Accept", "application/json")
                put("Cache-Control", "no-store")
                if (method == "POST") put("Content-Type", "application/json")
                if (token != null) put("Authorization", "Bearer $token")
            },
            body = body
        ).takeIf { it.body.length <= 64 * 1024 }
    } catch (error: CancellationException) {
        throw error
    } catch (_: Throwable) {
        null
    }

    suspend fun bootstrap(
        clientId: String,
        existingSimklToken: String,
        installationId: String
    ): RewatchBootstrapResult {
        if (clientId.isBlank() || existingSimklToken.isBlank() ||
            !isValidRewatchInstallationUuid(installationId)) {
            return RewatchBootstrapResult.InvalidResponse
        }
        val raw = call(
            "POST", "/v1/simkl/bootstrap", null,
            json.encodeToString(BootstrapRequest(clientId, existingSimklToken, installationId))
        ) ?: return RewatchBootstrapResult.Unavailable
        if (raw.status == 401 || raw.status == 403) {
            return RewatchBootstrapResult.AuthenticationFailed
        }
        if (raw.status != 201) return RewatchBootstrapResult.Unavailable
        val parsed = runCatching { json.decodeFromString<BootstrapResponse>(raw.body) }
            .getOrNull() ?: return RewatchBootstrapResult.InvalidResponse
        if (parsed.schemaVersion != 1 ||
            !Regex("[1-9][0-9]{0,15}").matches(parsed.simklAccountId) ||
            parsed.simklAccountId.toLongOrNull() == null) {
            return RewatchBootstrapResult.InvalidResponse
        }
        return RewatchBootstrapResult.Connected(
            simklAccountId = parsed.simklAccountId,
            credential = ReplayBootstrapCredential(
                simklAccountId = parsed.simklAccountId,
                token = parsed.token,
                deviceId = parsed.deviceId,
                expiresAtEpochMs = parsed.expiresAtEpochMs
            )
        )
    }

    suspend fun list(credential: ReplayBootstrapCredential): RewatchVpsReadResult {
        val raw = call("GET", "/v1/rewatches", credential.token, "")
            ?: return RewatchVpsReadResult.Unavailable
        if (raw.status == 401 || raw.status == 403) return RewatchVpsReadResult.Unauthorized
        if (raw.status != 200) return RewatchVpsReadResult.Unavailable
        // Do not promote server rows to Continue Watching until the caller has
        // verified owner/series identity and applied the reconciliation policy.
        return RewatchVpsReadResult.Received(raw.body)
    }

    suspend fun submit(
        credential: ReplayBootstrapCredential,
        event: RewatchQueuedEvent
    ): RewatchOutboxSubmitResult {
        if (credential.deviceId != event.deviceId) return RewatchOutboxSubmitResult.Unauthorized
        val payload = runCatching {
            json.parseToJsonElement(event.payload).jsonObject
        }.getOrNull() ?: return RewatchOutboxSubmitResult.Conflict
        val literal = { key: String -> payload[key]?.toString()?.trim('"') }
        if (literal("eventId") != event.eventId ||
            literal("deviceId") != event.deviceId ||
            literal("canonicalSeriesId") != event.canonicalSeriesId ||
            literal("sessionId") != event.sessionId ||
            literal("expectedRevision") != event.expectedRevision.toString()) {
            return RewatchOutboxSubmitResult.Conflict
        }
        val raw = call("POST", "/v1/rewatches/events", credential.token, event.payload)
            ?: return RewatchOutboxSubmitResult.Unavailable
        if (raw.status == 401 || raw.status == 403) return RewatchOutboxSubmitResult.Unauthorized
        if (raw.status == 409) return RewatchOutboxSubmitResult.Conflict
        if (raw.status != 200) return RewatchOutboxSubmitResult.Unavailable
        // Server must confirm monotonic revision before an outbox event is dropped.
        val revision = runCatching {
            val response = json.parseToJsonElement(raw.body).jsonObject
            response["revision"]?.toString()?.toLongOrNull()
        }.getOrNull()
        if (revision == null || revision <= event.expectedRevision) {
            return RewatchOutboxSubmitResult.Unavailable
        }
        return RewatchOutboxSubmitResult.Accepted(revision)
    }
}
