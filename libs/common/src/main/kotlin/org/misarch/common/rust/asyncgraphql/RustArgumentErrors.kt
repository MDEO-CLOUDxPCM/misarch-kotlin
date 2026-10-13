package org.misarch.common.rust.asyncgraphql

import graphql.GraphqlErrorBuilder
import graphql.execution.DataFetcherExceptionHandlerParameters
import graphql.execution.DataFetcherExceptionHandlerResult
import org.misarch.common.graphql.GraphQLErrorException
import org.misarch.common.graphql.MisarchDataFetcherExceptionHandler
import java.util.concurrent.CompletableFuture

/**
 * An argument that async-graphql could not parse into the original's Rust type (e.g. a negative `Int` for a `u32`).
 * async-graphql reports it as a field error located at the **argument value** (literal or `$variable`), not at the
 * field.
 *
 * @property argument name of the field argument whose value failed to parse
 */
class RustArgumentParseException(message: String, val argument: String) : GraphQLErrorException(message)

/**
 * libs/common's exception handling (plain message for [GraphQLErrorException]) plus the location rule of
 * [RustArgumentParseException].
 */
class AsyncGraphqlDataFetcherExceptionHandler : MisarchDataFetcherExceptionHandler() {

    override fun handleException(
        handlerParameters: DataFetcherExceptionHandlerParameters
    ): CompletableFuture<DataFetcherExceptionHandlerResult> {
        val exception = generateSequence(handlerParameters.exception) { it.cause }.take(10)
            .filterIsInstance<RustArgumentParseException>().firstOrNull()
            ?: return super.handleException(handlerParameters)
        val location = handlerParameters.dataFetchingEnvironment?.field?.arguments
            ?.firstOrNull { it.name == exception.argument }?.value?.sourceLocation
            ?: handlerParameters.sourceLocation
        val error = GraphqlErrorBuilder.newError()
            .message(exception.message ?: "")
            .path(handlerParameters.path)
            .location(location)
            .build()
        return CompletableFuture.completedFuture(DataFetcherExceptionHandlerResult.newResult().error(error).build())
    }
}
