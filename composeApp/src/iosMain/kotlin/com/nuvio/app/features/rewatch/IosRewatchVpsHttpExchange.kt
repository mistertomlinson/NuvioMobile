package com.nuvio.app.features.rewatch

import com.nuvio.app.core.network.createApiHttpClient
import com.nuvio.app.core.network.readBoundedResponseBody
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpMethod
import io.ktor.http.HttpHeaders

/**
 * iPad's dedicated HTTPS replay transport.
 * The shared Darwin client explicitly disables redirects and does not throw
 * on 401/409/503, allowing the outbox to preserve unsent replay events.
 *
 * This object is not invoked by app startup or playback yet.
 */
internal object IosRewatchVpsHttpExchange : RewatchHttpExchange {
    private val client by lazy { createApiHttpClient() }

    override suspend fun request(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: String
    ): RewatchHttpResponse {
        require(url.startsWith("https://nuviorewatch.duckdns.org/"))
        require(method == "GET" || method == "POST")
        val replayHeaders = headers
        val requestMethod = method
        val response = client.request(url) {
            this.method = HttpMethod.parse(requestMethod)
            replayHeaders.forEach { (key, value) -> this.headers.append(key, value) }
            if (requestMethod == "POST") setBody(body)
        }
        return RewatchHttpResponse(
            status = response.status.value,
            body = readBoundedResponseBody(
                channel = response.bodyAsChannel(),
                contentLength = response.headers[HttpHeaders.ContentLength]?.toLongOrNull(),
                maxBytes = 64 * 1024
            )
        )
    }
}
