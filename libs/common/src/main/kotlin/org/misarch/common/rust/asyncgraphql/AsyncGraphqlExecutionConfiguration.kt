package org.misarch.common.rust.asyncgraphql

import com.expediagroup.graphql.generator.federation.execution.FederatedTypeResolver
import com.expediagroup.graphql.server.types.GraphQLResponse
import com.fasterxml.jackson.annotation.JsonInclude
import graphql.execution.instrumentation.Instrumentation
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * The execution part of the async-graphql layer, importable on its own (used by [AsyncGraphqlConfiguration] for
 * shoppingcart/wishlist and directly by the order port, whose scalars, header parsing and routes are its own):
 * - [AsyncGraphqlSemanticsInstrumentation]: `_entities` all-or-nothing, an error anywhere -> `data: null` + the first
 *   error only, serial mutations stop at the first failing field,
 * - `"data"` is always written (also `"data": null`); graphql-kotlin's response omits a null `data`.
 * All Rust originals use async-graphql 7.0.16/7.0.17 (same execution semantics).
 */
@Configuration(proxyBeanMethods = false)
class AsyncGraphqlExecutionConfiguration {

    @Bean
    fun asyncGraphqlSemanticsInstrumentation(resolvers: List<FederatedTypeResolver>): Instrumentation =
        AsyncGraphqlSemanticsInstrumentation(resolvers)

    /**
     * async-graphql always writes `"data"` (also `"data": null`); graphql-kotlin's response omits null properties.
     */
    @Bean
    fun graphQLResponseDataAlwaysCustomizer() = JsonMapperBuilderCustomizer { builder ->
        builder.addMixIn(GraphQLResponse::class.java, GraphQLResponseDataAlwaysMixin::class.java)
    }

    /** Jackson mix-in for [GraphQLResponse]: `data` is written even if null. */
    abstract class GraphQLResponseDataAlwaysMixin {
        @JsonInclude(JsonInclude.Include.ALWAYS)
        abstract fun getData(): Any?
    }
}
