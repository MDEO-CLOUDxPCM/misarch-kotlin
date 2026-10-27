package org.misarch.common.graphql

import com.expediagroup.graphql.generator.extensions.plus
import com.expediagroup.graphql.server.spring.execution.DefaultSpringGraphQLContextFactory
import graphql.GraphQLContext
import org.misarch.common.timing.TimingSpans
import org.springframework.web.reactive.function.server.ServerRequest
import tools.jackson.databind.ObjectMapper

/**
 * Creates the [GraphQLContext] per request and puts the [AuthorizedUser] parsed from the `Authorized-User` header
 * into it (key: `AuthorizedUser::class`). Missing header -> no user in the context (anonymous request).
 *
 * @param objectMapper used to parse the header JSON
 */
open class AuthorizedUserGraphQLContextFactory(
    private val objectMapper: ObjectMapper
) : DefaultSpringGraphQLContextFactory() {

    override suspend fun generateContext(request: ServerRequest): GraphQLContext {
        val userHeader = request.headers().firstHeader(AuthorizedUser.HEADER_NAME)
        val additionalContext: Map<Any, Any> = if (userHeader != null) {
            mapOf(AuthorizedUser::class to objectMapper.readValue(userHeader, AuthorizedUser::class.java))
        } else {
            emptyMap()
        }
        return TimingSpans.withTraceContext(super.generateContext(request) + additionalContext)
    }
}
