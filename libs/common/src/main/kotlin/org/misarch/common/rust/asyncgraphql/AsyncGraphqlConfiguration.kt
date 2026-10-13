package org.misarch.common.rust.asyncgraphql

import com.expediagroup.graphql.server.spring.execution.SpringGraphQLContextFactory
import com.expediagroup.graphql.server.spring.execution.SpringGraphQLRequestParser
import graphql.execution.DataFetcherExceptionHandler
import org.misarch.common.graphql.ScalarMapping
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import tools.jackson.databind.ObjectMapper
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Opt-in async-graphql compatibility layer of the shoppingcart and wishlist ports (behaviour of the Rust original's
 * GraphQL stack, axum 0.8 + async-graphql 7.0.17): `@Import(AsyncGraphqlConfiguration::class)` on the application
 * class, `graphql.endpoint: /`, and a `dapr-subscribe.json` resource in the service (see [AsyncGraphqlRoutes]).
 *
 * Wires: Rust `UUID`/`DateTime` scalars ([AsyncGraphqlScalars]), lenient `Authorized-User` parsing
 * ([RustAuthorizedUserGraphQLContextFactory]), async-graphql error/entity semantics
 * ([AsyncGraphqlSemanticsInstrumentation]), argument-located parse errors ([AsyncGraphqlDataFetcherExceptionHandler]),
 * no batching ([NoBatchGraphQLRequestParser]), `"data": null` in error responses ([AsyncGraphqlExecutionConfiguration]),
 * and the HTTP routes of
 * [AsyncGraphqlRoutes]. Event routes: [AxumJsonHandler].
 *
 * This is a different variant than [org.misarch.common.rust.RustCompatConfiguration] (review, invoice, media), e.g.
 * `_entities` (sequential, null → `Entity not found.`, data null + first error only) and request parsing (no
 * multipart) differ; see docs/PORTING.md §11.
 */
@Configuration(proxyBeanMethods = false)
@Import(AsyncGraphqlRoutes::class, AsyncGraphqlExecutionConfiguration::class)
class AsyncGraphqlConfiguration {

    /** Overrides the default `UUID` scalar of libs/common (same class key). */
    @Bean
    fun rustUuidScalar() = ScalarMapping(UUID::class, AsyncGraphqlScalars.UUID)

    /** Overrides the default `DateTime` scalar of libs/common (same class key). */
    @Bean
    fun rustDateTimeScalar() = ScalarMapping(OffsetDateTime::class, AsyncGraphqlScalars.DATE_TIME)

    /** Replaces libs/common's context factory (which fails the request for an unparsable header). */
    @Bean
    fun rustAuthorizedUserGraphQLContextFactory(): SpringGraphQLContextFactory = RustAuthorizedUserGraphQLContextFactory()

    /** Batched requests are rejected like in the original. */
    @Bean
    fun noBatchGraphQLRequestParser(objectMapper: ObjectMapper): SpringGraphQLRequestParser =
        NoBatchGraphQLRequestParser(objectMapper)

    /** Replaces libs/common's handler (adds the argument-value location of [RustArgumentParseException]). */
    @Bean
    fun rustDataFetcherExceptionHandler(): DataFetcherExceptionHandler = AsyncGraphqlDataFetcherExceptionHandler()
}
