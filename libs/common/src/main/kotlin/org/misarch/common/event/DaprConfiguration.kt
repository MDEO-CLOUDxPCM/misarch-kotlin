package org.misarch.common.event

import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.reactive.function.client.WebClient
import tools.jackson.databind.ObjectMapper

/**
 * Dapr integration shared by all services.
 * - Subscriptions: `@io.dapr.Topic` + `@PostMapping` on controller methods; the Dapr Spring Boot integration
 *   (`dapr-sdk-springboot`) serves `GET /dapr/subscribe`, `GET /dapr/config` and `GET /healthz`, like in the original
 *   Kotlin services.
 * - Publishing ([EventPublisher]) and service invocation ([DaprServiceInvoker]) use the sidecar's HTTP API.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DaprProperties::class)
class DaprConfiguration {

    @Bean
    @Qualifier(DAPR_WEB_CLIENT)
    @ConditionalOnMissingBean(name = [DAPR_WEB_CLIENT])
    fun daprWebClient(): WebClient = WebClient.builder().build()

    @Bean
    @ConditionalOnMissingBean(EventPublisher::class)
    fun eventPublisher(@Qualifier(DAPR_WEB_CLIENT) webClient: WebClient, properties: DaprProperties): EventPublisher =
        EventPublisher(webClient, properties)

    @Bean
    @ConditionalOnMissingBean(DaprServiceInvoker::class)
    fun daprServiceInvoker(
        @Qualifier(DAPR_WEB_CLIENT) webClient: WebClient,
        properties: DaprProperties,
        objectMapper: ObjectMapper
    ): DaprServiceInvoker = DaprServiceInvoker(webClient, properties, objectMapper)

    companion object {
        const val DAPR_WEB_CLIENT = "daprWebClient"
    }
}
