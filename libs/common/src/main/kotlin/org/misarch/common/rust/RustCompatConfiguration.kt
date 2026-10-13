package org.misarch.common.rust

import com.expediagroup.graphql.generator.federation.execution.FederatedTypeResolver
import com.expediagroup.graphql.server.spring.execution.SpringGraphQLContextFactory
import com.expediagroup.graphql.server.spring.execution.SpringGraphQLRequestParser
import com.expediagroup.graphql.server.spring.execution.SpringGraphQLServer
import com.expediagroup.graphql.server.types.GraphQLBatchResponse
import com.expediagroup.graphql.server.types.GraphQLResponse
import com.expediagroup.graphql.server.types.GraphQLServerResponse
import graphql.execution.DataFetcherExceptionHandler
import graphql.schema.FieldCoordinates
import graphql.schema.GraphQLSchema
import org.misarch.common.graphql.ScalarMapping
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.server.RouterFunction
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.bodyValueAndAwait
import org.springframework.web.reactive.function.server.buildAndAwait
import org.springframework.web.reactive.function.server.coRouter
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.UUID

/**
 * Opt-in setup for ports of the Rust (axum + async-graphql) services: `@Import(RustCompatConfiguration::class)` on
 * the application class, plus `graphql.endpoint: /` in `application.yaml`.
 *
 * - scalars: `UUID` = [RustUuid.SCALAR] (lenient uuid-crate parsing), `DateTime` for [Instant] = [RustDateTime.SCALAR]
 *   (chrono `to_rfc3339()`, `+00:00`),
 * - `Authorized-User` parsed like `authorization.rs` ([RustGraphQLContextFactory]); check with
 *   `dfe.rustAuthorizeUser(id)`,
 * - `_entities` with async-graphql semantics ([RustEntitiesDataFetcher]),
 * - `POST /`: GraphQL (single JSON or multipart request; batch → 400; a [RustPanic] → 500, empty body),
 *   `GET /`: GraphiQL page (async-graphql `GraphiQLSource`), `GET /health`: 200 with an empty body (set
 *   `management.endpoints.web.exposure.exclude: "*"` so that the actuator does not answer it first),
 * - plain error messages for [org.misarch.common.graphql.GraphQLErrorException] (as in libs/common).
 * Event routes: see [AxumJson] and [RustDaprRoutes].
 */
@Configuration(proxyBeanMethods = false)
class RustCompatConfiguration {

    @Bean
    fun rustUuidScalar() = ScalarMapping(UUID::class, RustUuid.SCALAR)

    @Bean
    fun rustDateTimeScalar() = ScalarMapping(Instant::class, RustDateTime.SCALAR)

    @Bean
    fun rustGraphQLContextFactory(): SpringGraphQLContextFactory = RustGraphQLContextFactory()

    @Bean
    fun rustDataFetcherExceptionHandler(): DataFetcherExceptionHandler = RustDataFetcherExceptionHandler()

    @Bean
    fun rustGraphQLRequestParser(objectMapper: ObjectMapper): SpringGraphQLRequestParser =
        RustGraphQLRequestParser(objectMapper)

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    fun rustGraphQLRoutes(
        server: ObjectProvider<SpringGraphQLServer>,
        objectMapper: ObjectMapper,
    ): RouterFunction<ServerResponse> = coRouter {
        POST("/") { request ->
            val flag = RustPanicFlag()
            request.exchange().attributes[RustPanicFlag.ATTRIBUTE] = flag
            val response = try {
                server.getObject().execute(request)
            } catch (e: Exception) {
                null
            }
            when {
                flag.panicked -> status(HttpStatus.INTERNAL_SERVER_ERROR).buildAndAwait()
                response == null -> badRequest().buildAndAwait()
                else -> ok().contentType(MediaType.APPLICATION_JSON)
                    .bodyValueAndAwait(objectMapper.writeValueAsString(asyncGraphQLResponse(response)))
            }
        }
        GET("/") {
            ok().contentType(MediaType.TEXT_HTML).bodyValueAndAwait(GRAPHIQL_HTML)
        }
        // axum `/health` of the originals: 200, empty body. Only reachable if the service switches the actuator's web
        // endpoints off (`management.endpoints.web.exposure.exclude: "*"`), which all users do.
        GET("/health") {
            ok().buildAndAwait()
        }
    }

    companion object {
        /**
         * async-graphql's response shape: `data` always present (also `null`), then `extensions` and `errors` only if
         * non-empty (graphql-kotlin omits a `null` data).
         */
        fun asyncGraphQLResponse(response: GraphQLServerResponse): Any = when (response) {
            is GraphQLResponse<*> -> linkedMapOf<String, Any?>("data" to response.data).apply {
                if (!response.extensions.isNullOrEmpty()) put("extensions", response.extensions)
                if (!response.errors.isNullOrEmpty()) put("errors", response.errors)
            }
            is GraphQLBatchResponse -> response.responses.map(::asyncGraphQLResponse)
        }

        /** Replaces the `_entities` data fetcher of the federated schema by [RustEntitiesDataFetcher]. */
        @Bean
        @JvmStatic
        fun rustEntitiesPostProcessor(resolvers: ObjectProvider<FederatedTypeResolver>): BeanPostProcessor =
            object : BeanPostProcessor {
                override fun postProcessAfterInitialization(bean: Any, beanName: String): Any {
                    if (bean !is GraphQLSchema) return bean
                    val query = bean.queryType
                    if (query.getFieldDefinition("_entities") == null) return bean
                    val fetcher = RustEntitiesDataFetcher(resolvers.orderedStream().toList())
                    val registry = bean.codeRegistry.transform {
                        it.dataFetcher(FieldCoordinates.coordinates(query.name, "_entities"), fetcher)
                    }
                    return bean.transform { it.codeRegistry(registry) }
                }
            }

        /** GraphiQL page like async-graphql's `GraphiQLSource::build().endpoint("/")`. */
        const val GRAPHIQL_HTML: String = """<!DOCTYPE html>
<html lang="en">
  <head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <meta name="robots" content="noindex">
    <meta name="referrer" content="origin">
    <title>GraphiQL IDE</title>
    <style>body { height: 100%; margin: 0; width: 100%; overflow: hidden; } #graphiql { height: 100vh; }</style>
    <script crossorigin src="https://unpkg.com/react@17/umd/react.development.js"></script>
    <script crossorigin src="https://unpkg.com/react-dom@17/umd/react-dom.development.js"></script>
    <link rel="icon" href="https://graphql.org/favicon.ico">
    <link rel="stylesheet" href="https://unpkg.com/graphiql/graphiql.min.css" />
  </head>
  <body>
    <div id="graphiql">Loading...</div>
    <script src="https://unpkg.com/graphiql/graphiql.min.js" type="application/javascript"></script>
    <script>
      ReactDOM.render(
        React.createElement(GraphiQL, { fetcher: GraphiQL.createFetcher({ url: '/' }), defaultEditorToolsVisibility: true }),
        document.getElementById("graphiql")
      );
    </script>
  </body>
</html>"""
    }
}

/**
 * Dapr routes of the Rust originals: `GET /dapr/subscribe` answered with the original's exact JSON (a functional
 * route, so it takes precedence over the Dapr Spring Boot `DaprController`), or 404 for services without one.
 */
object RustDaprRoutes {
    fun subscribe(json: String): RouterFunction<ServerResponse> = coRouter {
        GET("/dapr/subscribe") { ok().contentType(MediaType.APPLICATION_JSON).bodyValueAndAwait(json) }
    }

    fun noSubscribe(): RouterFunction<ServerResponse> = coRouter {
        GET("/dapr/subscribe") { status(HttpStatus.NOT_FOUND).buildAndAwait() }
    }

    /** The success answer of the Rust event handlers: 200 `{"status":0}`. */
    const val TOPIC_EVENT_OK = """{"status":0}"""
}
