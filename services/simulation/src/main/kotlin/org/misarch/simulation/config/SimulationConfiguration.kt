package org.misarch.simulation.config

import io.netty.channel.ChannelOption
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.http.codec.json.JacksonJsonEncoder
import org.springframework.web.reactive.function.client.WebClient
import reactor.netty.http.client.HttpClient
import tools.jackson.databind.json.JsonMapper
import kotlin.random.Random

/**
 * Beans of the simulation: the HTTP client of the status callbacks and the source of the random outcomes.
 */
@Configuration(proxyBeanMethods = false)
class SimulationConfiguration {

    /**
     * Client of the status callbacks: a new TCP connection per request, no connect or response timeout (the OS
     * decides), redirects are not followed; JSON bodies are written with the application's mapper.
     */
    @Bean
    fun callbackWebClient(jsonMapper: JsonMapper): WebClient = WebClient.builder()
        .clientConnector(
            // <= 0 disables Netty's connect timeout
            ReactorClientHttpConnector(HttpClient.newConnection().option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 0))
        )
        .codecs { it.defaultCodecs().jacksonJsonEncoder(JacksonJsonEncoder(jsonMapper)) }
        .build()

    /**
     * Source of the success/failure draws.
     */
    @Bean
    fun random(): Random = Random.Default
}
