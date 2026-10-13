package org.misarch.media

import graphql.GraphQLContext
import graphql.execution.CoercedVariables
import graphql.language.Value
import graphql.schema.Coercing
import graphql.schema.CoercingParseLiteralException
import graphql.schema.CoercingParseValueException
import graphql.schema.CoercingSerializeException
import graphql.schema.GraphQLScalarType
import org.misarch.common.graphql.ScalarMapping
import org.misarch.common.rust.RustDaprRoutes
import org.misarch.common.rust.RustUpload
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.web.reactive.function.server.RouterFunction
import org.springframework.web.reactive.function.server.ServerResponse
import java.util.Locale

/** Media-specific wiring: the `Upload` scalar (GraphQL multipart request files) and no Dapr subscriptions. */
@Configuration(proxyBeanMethods = false)
class MediaConfiguration {

    /** async-graphql's `Upload` scalar: only a file from a multipart request ([RustUpload]) is accepted. */
    @Bean
    fun uploadScalar() = ScalarMapping(RustUpload::class, UPLOAD)

    /** The original serves no `/dapr/subscribe` (axum 404 = no subscriptions for Dapr). */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    fun mediaDaprSubscribeRoute(): RouterFunction<ServerResponse> = RustDaprRoutes.noSubscribe()

    companion object {
        val UPLOAD: GraphQLScalarType = GraphQLScalarType.newScalar()
            .name("Upload")
            .coercing(object : Coercing<RustUpload, Nothing> {
                override fun serialize(dataFetcherResult: Any, graphQLContext: GraphQLContext, locale: Locale): Nothing =
                    throw CoercingSerializeException("Upload is an input-only scalar")

                override fun parseValue(input: Any, graphQLContext: GraphQLContext, locale: Locale): RustUpload =
                    input as? RustUpload ?: throw CoercingParseValueException("Invalid upload value")

                override fun parseLiteral(
                    input: Value<*>, variables: CoercedVariables, graphQLContext: GraphQLContext, locale: Locale
                ): RustUpload = throw CoercingParseLiteralException("Invalid upload value")
            })
            .build()
    }
}
