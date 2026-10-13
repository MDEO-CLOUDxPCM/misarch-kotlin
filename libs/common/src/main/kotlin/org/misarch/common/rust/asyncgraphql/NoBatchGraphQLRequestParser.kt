package org.misarch.common.rust.asyncgraphql

import com.expediagroup.graphql.server.spring.execution.SpringGraphQLRequestParser
import com.expediagroup.graphql.server.types.GraphQLBatchRequest
import com.expediagroup.graphql.server.types.GraphQLServerRequest
import org.springframework.web.reactive.function.server.ServerRequest
import tools.jackson.databind.ObjectMapper

/**
 * The original (async-graphql-axum `GraphQLRequest`) does not accept batched requests (`400 UnsupportedBatch`).
 * Throwing here makes graphql-kotlin's route answer `400` (with an empty body).
 */
class NoBatchGraphQLRequestParser(objectMapper: ObjectMapper) : SpringGraphQLRequestParser(objectMapper) {

    override suspend fun parseRequest(request: ServerRequest): GraphQLServerRequest? {
        val parsed = super.parseRequest(request)
        if (parsed is GraphQLBatchRequest) {
            throw IllegalArgumentException("UnsupportedBatch")
        }
        return parsed
    }
}
