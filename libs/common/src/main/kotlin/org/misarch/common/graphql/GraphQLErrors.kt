package org.misarch.common.graphql

import graphql.GraphqlErrorBuilder
import graphql.execution.DataFetcherExceptionHandlerParameters
import graphql.execution.DataFetcherExceptionHandlerResult
import graphql.execution.SimpleDataFetcherExceptionHandler
import java.util.concurrent.CompletableFuture

/**
 * Throw from a resolver to produce a GraphQL error whose `message` is exactly [message].
 *
 * Any other exception becomes graphql-java's default `"Exception while fetching data (/path) : <message>"` - which is
 * what the original Kotlin services return. Throw this exception where the contract requires the plain message.
 *
 * @property extensions optional `extensions` of the error (e.g. `code` for NestJS-style errors)
 */
open class GraphQLErrorException(
    message: String,
    val extensions: Map<String, Any>? = null,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * graphql-java's default exception handling, except for [GraphQLErrorException] (plain message).
 */
open class MisarchDataFetcherExceptionHandler : SimpleDataFetcherExceptionHandler() {

    override fun handleException(
        handlerParameters: DataFetcherExceptionHandlerParameters
    ): CompletableFuture<DataFetcherExceptionHandlerResult> {
        val exception = generateSequence(handlerParameters.exception) { it.cause }.take(10)
            .filterIsInstance<GraphQLErrorException>().firstOrNull()
            ?: return super.handleException(handlerParameters)
        val error = GraphqlErrorBuilder.newError()
            .message(exception.message ?: "")
            .path(handlerParameters.path)
            .location(handlerParameters.sourceLocation)
            .apply { exception.extensions?.let { extensions(it) } }
            .build()
        return CompletableFuture.completedFuture(DataFetcherExceptionHandlerResult.newResult().error(error).build())
    }
}
