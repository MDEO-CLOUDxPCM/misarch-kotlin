package org.misarch.common.nest

import graphql.ExecutionResult
import graphql.GraphQLError
import graphql.execution.NonNullableFieldWasNullError
import graphql.execution.instrumentation.parameters.InstrumentationExecutionParameters
import graphql.GraphqlErrorBuilder
import graphql.execution.DataFetcherExceptionHandler
import graphql.execution.DataFetcherExceptionHandlerParameters
import graphql.execution.DataFetcherExceptionHandlerResult
import graphql.execution.DataFetcherResult
import graphql.execution.ResultPath
import graphql.execution.instrumentation.InstrumentationState
import graphql.execution.instrumentation.SimplePerformantInstrumentation
import graphql.execution.instrumentation.parameters.InstrumentationFieldFetchParameters
import graphql.language.SourceLocation
import graphql.schema.DataFetcher
import graphql.schema.DataFetchingEnvironment
import org.misarch.common.graphql.GraphQLErrorException
import org.slf4j.LoggerFactory
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutionException
import java.lang.reflect.InvocationTargetException

/**
 * GraphQL error formatting of the original (`@nestjs/apollo` 12 on Apollo Server 4, verified against the running
 * image): `message` = the exception message, `extensions`:
 * - 400 → `{code: "BAD_REQUEST", originalError}`, 401 → `{code: "UNAUTHENTICATED", originalError}`,
 *   403 → `{code: "FORBIDDEN", originalError}`, other HttpExceptions → `{code: "INTERNAL_SERVER_ERROR", status, originalError}`,
 * - every other error → `{code: "INTERNAL_SERVER_ERROR"}`.
 * The JS `extensions.stacktrace` is not reproduced.
 */
object NestGraphQLErrors {

    private val logger = LoggerFactory.getLogger(NestGraphQLErrors::class.java)

    fun unwrap(throwable: Throwable): Throwable {
        var current = throwable
        repeat(10) {
            val next = when (current) {
                is CompletionException, is ExecutionException, is InvocationTargetException -> current.cause
                else -> null
            } ?: return current
            current = next
        }
        return current
    }

    fun extensionsOf(error: Throwable): Map<String, Any?> = when (error) {
        is HttpException -> when (error.status) {
            400 -> linkedMapOf("code" to "BAD_REQUEST", "originalError" to error.response)
            401 -> linkedMapOf("code" to "UNAUTHENTICATED", "originalError" to error.response)
            403 -> linkedMapOf("code" to "FORBIDDEN", "originalError" to error.response)
            else -> linkedMapOf("code" to "INTERNAL_SERVER_ERROR", "status" to error.status, "originalError" to error.response)
        }
        is GraphQLErrorException -> error.extensions ?: linkedMapOf("code" to "INTERNAL_SERVER_ERROR")
        else -> linkedMapOf("code" to "INTERNAL_SERVER_ERROR")
    }

    fun messageOf(error: Throwable): String = error.message ?: error.javaClass.simpleName

    fun toGraphQLError(error: Throwable, path: ResultPath?, location: SourceLocation?): GraphQLError {
        val cause = unwrap(error)
        if (cause !is HttpException && cause !is JsError && cause !is GraphQLErrorException) {
            logger.error("Unexpected error in resolver at {}", path, cause)
        }
        @Suppress("UNCHECKED_CAST")
        return GraphqlErrorBuilder.newError()
            .message(messageOf(cause))
            .apply { if (path != null) path(path) }
            .apply { if (location != null) location(location) }
            .extensions(extensionsOf(cause) as Map<String, Any>)
            .build()
    }
}

/** [DataFetcherExceptionHandler] applying [NestGraphQLErrors] to every resolver error. */
class NestDataFetcherExceptionHandler : DataFetcherExceptionHandler {
    override fun handleException(
        handlerParameters: DataFetcherExceptionHandlerParameters
    ): CompletableFuture<DataFetcherExceptionHandlerResult> {
        val error = NestGraphQLErrors.toGraphQLError(
            handlerParameters.exception, handlerParameters.path, handlerParameters.sourceLocation
        )
        return CompletableFuture.completedFuture(DataFetcherExceptionHandlerResult.newResult().error(error).build())
    }
}

/**
 * Errors of federation reference resolvers. graphql-kotlin reports a failing entity resolver with its own
 * `FederatedRequestFailure` text and without a path; the original reports the resolver's error at
 * `["_entities", index]`. Entity resolvers therefore record their error here ([EntityErrors.record]) and return null;
 * [EntityErrorInstrumentation] replaces the errors of the `_entities` field by the recorded ones.
 */
object EntityErrors {
    private const val KEY = "nest.entityErrors"

    fun record(environment: DataFetchingEnvironment, representation: Map<String, Any>, error: Throwable) {
        val representations = environment.getArgument<List<Any?>>("representations") ?: emptyList()
        val index = representations.indexOfFirst { it === representation }.takeIf { it >= 0 }
            ?: representations.indexOf(representation)
        val queue = environment.graphQlContext.computeIfAbsent(KEY) { ConcurrentLinkedQueue<GraphQLError>() }
            as ConcurrentLinkedQueue<GraphQLError>
        val path = environment.executionStepInfo.path.segment(index)
        queue.add(NestGraphQLErrors.toGraphQLError(error, path, environment.field.sourceLocation))
    }

    fun drain(environment: DataFetchingEnvironment): List<GraphQLError> {
        val queue = environment.graphQlContext.get<ConcurrentLinkedQueue<GraphQLError>>(KEY) ?: return emptyList()
        val errors = mutableListOf<GraphQLError>()
        while (true) errors += queue.poll() ?: break
        return errors.sortedBy { (it.path?.getOrNull(1) as? Int) ?: 0 }
    }
}

/** Wraps the `Query._entities` data fetcher (see [EntityErrors]). */
class EntityErrorInstrumentation : SimplePerformantInstrumentation() {
    override fun instrumentDataFetcher(
        dataFetcher: DataFetcher<*>,
        parameters: InstrumentationFieldFetchParameters,
        state: InstrumentationState?
    ): DataFetcher<*> {
        val environment = parameters.environment
        if (environment.field.name != "_entities" || environment.parentType.let { it is graphql.schema.GraphQLObjectType && it.name != "Query" }) {
            return dataFetcher
        }
        return DataFetcher { env ->
            val result = dataFetcher.get(env)
            val future: CompletableFuture<Any?> = when (result) {
                is CompletableFuture<*> -> result.thenApply { it }
                else -> CompletableFuture.completedFuture(result)
            }
            future.thenApply { value ->
                val recorded = EntityErrors.drain(env)
                when (value) {
                    is DataFetcherResult<*> -> DataFetcherResult.newResult<Any?>()
                        .data(value.data)
                        .errors(recorded + value.errors.filterNot { isFederatedFailure(it) })
                        .build()
                    else -> if (recorded.isEmpty()) value else DataFetcherResult.newResult<Any?>().data(value).errors(recorded).build()
                }
            }
        }
    }

    private fun isFederatedFailure(error: GraphQLError) =
        error.javaClass.simpleName == "FederatedRequestFailure"

    /**
     * graphql-java adds a `NonNullableFieldWasNullError` (with its own long text) for a non-null field that resolved
     * to null; graphql-js reports `Cannot return null for non-nullable field Parent.field.` and only if the field
     * did not already fail. Rewrite / drop accordingly.
     */
    override fun instrumentExecutionResult(
        executionResult: ExecutionResult,
        parameters: InstrumentationExecutionParameters,
        state: InstrumentationState?
    ): CompletableFuture<ExecutionResult> {
        val errors = executionResult.errors
        if (errors.none { it is NonNullableFieldWasNullError }) return CompletableFuture.completedFuture(executionResult)
        val otherPaths = errors.filterNot { it is NonNullableFieldWasNullError }.mapNotNull { it.path }.toSet()
        val rewritten = errors.mapNotNull { error ->
            if (error !is NonNullableFieldWasNullError) return@mapNotNull error
            if (error.path in otherPaths) return@mapNotNull null
            val parentType = NON_NULL_PARENT.find(error.message)?.groupValues?.get(1)
            val field = error.path?.lastOrNull { it is String } as String?
            if (parentType == null || field == null) return@mapNotNull error
            GraphqlErrorBuilder.newError()
                .message("Cannot return null for non-nullable field $parentType.$field.")
                .path(error.path)
                .locations(error.locations)
                .extensions(mapOf("code" to "INTERNAL_SERVER_ERROR"))
                .build()
        }
        return CompletableFuture.completedFuture(executionResult.transform { it.errors(rewritten) })
    }

    companion object {
        private val NON_NULL_PARENT = Regex("within parent type '([^']+)'")
    }

}
