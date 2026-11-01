package org.misarch.simulation.connector

import io.netty.channel.ChannelOption
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.http.MediaType
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import reactor.netty.http.client.HttpClient
import java.net.URI

/**
 * HTTP client of the callbacks: a new TCP connection per request, no connect or response timeout, JSON body,
 * redirects are not followed.
 */
@Component
class WebClientCallbackTransport : CallbackTransport {

    private val webClient = WebClient.builder()
        .clientConnector(
            ReactorClientHttpConnector(
                HttpClient.newConnection()
                    // <= 0 disables Netty's connect timeout (the OS decides)
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 0)
            )
        )
        .build()

    override suspend fun post(uri: URI, json: String): Int =
        webClient.post()
            .uri(uri)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(json)
            .exchangeToMono { response -> response.releaseBody().thenReturn(response.statusCode().value()) }
            .awaitSingle()
}
