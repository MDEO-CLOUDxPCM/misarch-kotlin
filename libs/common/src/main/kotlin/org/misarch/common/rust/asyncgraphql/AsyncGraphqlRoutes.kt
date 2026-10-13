package org.misarch.common.rust.asyncgraphql

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.core.io.ClassPathResource
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.server.RouterFunction
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.bodyValueAndAwait
import org.springframework.web.reactive.function.server.buildAndAwait
import org.springframework.web.reactive.function.server.coRouter
import org.springframework.web.server.WebFilter

/**
 * HTTP routes of the Rust original's axum router that are not GraphQL executions (imported by
 * [AsyncGraphqlConfiguration]):
 * - `GET /` -> async-graphql's GraphiQL page (HTML, endpoint `/`, resource [GRAPHIQL_RESOURCE]); POST `/` is
 *   graphql-kotlin (`graphql.endpoint: /`),
 * - `GET /health` -> `200` with an empty body (no DB check),
 * - `GET /dapr/subscribe` -> the original's subscription list, byte-identical: the **service's** classpath resource
 *   [SUBSCRIPTIONS_RESOURCE].
 * Router functions are consulted before annotated controllers (the Dapr SDK's `DaprController`), and this one is
 * ordered before graphql-kotlin's router.
 */
@Configuration(proxyBeanMethods = false)
class AsyncGraphqlRoutes {

    private val graphiql = ClassPathResource(GRAPHIQL_RESOURCE).inputStream.use { it.readBytes().decodeToString() }
    private val subscriptions = ClassPathResource(SUBSCRIPTIONS_RESOURCE).inputStream.use { it.readBytes().decodeToString() }

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    fun compatRouter(): RouterFunction<ServerResponse> = coRouter {
        GET("/") {
            ServerResponse.ok().contentType(MediaType.parseMediaType("text/html; charset=utf-8")).bodyValueAndAwait(graphiql)
        }
        GET("/health") {
            ServerResponse.ok().buildAndAwait()
        }
        GET("/dapr/subscribe") {
            ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).bodyValueAndAwait(subscriptions)
        }
    }

    /**
     * async-graphql-axum 7.0.17 (the deployed original) answers every GraphQL execution with
     * `Content-Type: application/graphql-response+json`, whatever the `Accept` header says.
     */
    @Bean
    fun graphqlResponseContentTypeFilter(): WebFilter = WebFilter { exchange, chain ->
        val request = exchange.request
        if (request.method == HttpMethod.POST && request.path.value() == "/") {
            exchange.response.beforeCommit {
                val response = exchange.response
                if (response.statusCode?.value() == 200) {
                    response.headers.contentType = GRAPHQL_RESPONSE_JSON
                }
                reactor.core.publisher.Mono.empty()
            }
        }
        chain.filter(exchange)
    }

    companion object {
        val GRAPHQL_RESPONSE_JSON: MediaType = MediaType.parseMediaType("application/graphql-response+json")

        /** GraphiQL page of async-graphql 7 (`GraphiQLSource`), shipped with libs/common. */
        const val GRAPHIQL_RESOURCE = "org/misarch/common/rust/asyncgraphql/graphiql.html"

        /** The service's verbatim `GET /dapr/subscribe` answer (`src/main/resources/dapr-subscribe.json`). */
        const val SUBSCRIPTIONS_RESOURCE = "dapr-subscribe.json"
    }
}
