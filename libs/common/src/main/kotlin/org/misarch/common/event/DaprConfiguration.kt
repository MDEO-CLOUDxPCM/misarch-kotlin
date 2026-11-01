package org.misarch.common.event

import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Scope
import org.springframework.web.reactive.function.client.WebClient
import tools.jackson.databind.ObjectMapper

/**
 * Dapr integration shared by all services.
 * - Subscriptions: `@io.dapr.Topic` + `@PostMapping` on controller methods; the Dapr Spring Boot integration
 *   (`dapr-sdk-springboot`) serves `GET /dapr/subscribe`, `GET /dapr/config` and `GET /healthz`.
 * - Publishing ([EventPublisher]) and service invocation ([DaprServiceInvoker]) use the sidecar's HTTP API.
 * - [WebClient.Builder]: the builder every HTTP client of a service starts from (a new builder per injection point,
 *   default codecs); clients build their [WebClient] once.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DaprProperties::class)
class DaprConfiguration {

    @Bean
    @Scope("prototype")
    @ConditionalOnMissingBean(WebClient.Builder::class)
    fun webClientBuilder(): WebClient.Builder = WebClient.builder()

    @Bean
    @Qualifier(DAPR_WEB_CLIENT)
    @ConditionalOnMissingBean(name = [DAPR_WEB_CLIENT])
    fun daprWebClient(webClientBuilder: WebClient.Builder): WebClient = webClientBuilder.build()

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
