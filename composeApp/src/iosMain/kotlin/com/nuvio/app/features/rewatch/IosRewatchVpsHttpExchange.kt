package com.nuvio.app.features.rewatch

import com.nuvio.app.core.network.createApiHttpClient
import com.nuvio.app.core.network.readBoundedResponseBody
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.contentLength
import io.ktor.http.HttpMethod
import io.ktor.http.headers

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
        val response = client.request(url) {
            this.method = HttpMethod.parse(method)
            headers {
                replayHeaders.forEach { (key, value) -> append(key, value) }
            }
            if (method == "POST") setBody(body)
        }
        return RewatchHttpResponse(
            status = response.status.value,
            body = readBoundedResponseBody(
                channel = response.bodyAsChannel(),
                contentLength = response.contentLength(),
                maxBytes = 64 * 1024
            )
        )
    }
}
