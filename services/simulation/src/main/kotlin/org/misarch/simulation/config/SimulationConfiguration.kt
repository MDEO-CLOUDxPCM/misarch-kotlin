package org.misarch.simulation.config

import io.netty.channel.ChannelOption
import org.misarch.simulation.model.RegisterPaymentDTO
import org.misarch.simulation.model.RegisterShipmentDTO
import org.misarch.simulation.model.SimulationQueue
import org.misarch.simulation.service.CallbackService
import org.misarch.simulation.service.RegistrationService
import org.springframework.amqp.core.Queue
import org.springframework.amqp.rabbit.core.RabbitTemplate
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.http.codec.json.JacksonJsonEncoder
import org.springframework.web.reactive.function.client.WebClient
import reactor.netty.http.client.HttpClient
import tools.jackson.databind.json.JsonMapper
import kotlin.random.Random

/**
 * Beans of the simulation: the queues, the registrations of both providers, the HTTP client of the status callbacks
 * and the source of the random outcomes.
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

    /** The durable queue of the payment registrations (declared on connect). */
    @Bean
    fun paymentsQueue(): Queue = Queue(SimulationQueue.PAYMENTS.queueName)

    /** The durable queue of the shipment registrations (declared on connect). */
    @Bean
    fun shipmentsQueue(): Queue = Queue(SimulationQueue.SHIPMENTS.queueName)

    /** The registrations of the payment provider. */
    @Bean
    fun paymentRegistrationService(
        rabbitTemplate: RabbitTemplate,
        jsonMapper: JsonMapper,
        callbackService: CallbackService,
    ): RegistrationService<RegisterPaymentDTO> =
        RegistrationService(SimulationQueue.PAYMENTS, rabbitTemplate, jsonMapper, callbackService)

    /** The registrations of the shipment provider. */
    @Bean
    fun shipmentRegistrationService(
        rabbitTemplate: RabbitTemplate,
        jsonMapper: JsonMapper,
        callbackService: CallbackService,
    ): RegistrationService<RegisterShipmentDTO> =
        RegistrationService(SimulationQueue.SHIPMENTS, rabbitTemplate, jsonMapper, callbackService)

}
