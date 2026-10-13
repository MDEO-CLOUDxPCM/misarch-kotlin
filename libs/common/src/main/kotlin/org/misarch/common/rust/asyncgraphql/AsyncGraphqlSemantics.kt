package org.misarch.common.rust.asyncgraphql

import com.expediagroup.graphql.generator.federation.execution.FederatedTypePromiseResolver
import com.expediagroup.graphql.generator.federation.execution.FederatedTypeResolver
import com.expediagroup.graphql.generator.federation.execution.FederatedTypeSuspendResolver
import graphql.ExecutionResult
import graphql.GraphQLError
import graphql.GraphqlErrorBuilder
import graphql.execution.instrumentation.InstrumentationState
import graphql.execution.instrumentation.SimplePerformantInstrumentation
import graphql.execution.instrumentation.parameters.InstrumentationExecutionParameters
import graphql.execution.instrumentation.parameters.InstrumentationFieldFetchParameters
import graphql.language.OperationDefinition
import graphql.schema.DataFetcher
import graphql.schema.DataFetchingEnvironment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.future.await
import kotlinx.coroutines.future.future
import org.misarch.common.graphql.GraphQLErrorException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Reproduces the execution semantics of async-graphql (the GraphQL library of the Rust originals) on top of
 * graphql-java, as far as they are observable for these services (every output field of the schema is non-null):
 *
 * 1. `_entities` ([AsyncGraphqlEntitiesDataFetcher]): all-or-nothing, original error messages.
 * 2. Error propagation: an error anywhere makes the whole `data` null (async-graphql stops at the first error of a
 *    non-null field and propagates it to the root) and only the **first** error is reported. graphql-java would
 *    null only the nearest nullable parent (an `_entities` list element) and report every error.
 *    Errors inside an entity of `_entities` are reported without the list index
 *    (e.g. `["_entities", "wishlists"]`), as async-graphql does.
 * 3. Mutations run serially and stop at the first failing field: later mutation fields are not executed
 *    (graphql-java's serial strategy would still run them and their side effects).
 *
 * Request-level errors (syntax, validation, variable coercion) are left as graphql-java produces them.
 */
class AsyncGraphqlSemanticsInstrumentation(resolvers: List<FederatedTypeResolver>) : SimplePerformantInstrumentation() {

    private val entitiesDataFetcher = AsyncGraphqlEntitiesDataFetcher(resolvers)

    override fun instrumentDataFetcher(
        dataFetcher: DataFetcher<*>,
        parameters: InstrumentationFieldFetchParameters,
        state: InstrumentationState?
    ): DataFetcher<*> {
        val operation = parameters.executionContext.operationDefinition.operation
        val isRootField = parameters.executionStepInfo.path.level == 1
        val fieldName = parameters.executionStepInfo.fieldDefinition?.name
        if (operation == OperationDefinition.Operation.QUERY && isRootField && fieldName == ENTITIES_FIELD) {
            return entitiesDataFetcher
        }
        if (operation != OperationDefinition.Operation.MUTATION) {
            return dataFetcher
        }
        return DataFetcher { env ->
            val failed = env.graphQlContext.computeIfAbsent(MUTATION_FAILED_KEY) { AtomicBoolean(false) } as AtomicBoolean
            if (isRootField && failed.get()) {
                // async-graphql does not execute mutation fields after a failed one; the error is never reported
                throw GraphQLErrorException("mutation not executed: an earlier mutation field failed")
            }
            val result = try {
                dataFetcher.get(env)
            } catch (e: Throwable) {
                failed.set(true)
                throw e
            }
            if (result is CompletionStage<*>) {
                result.toCompletableFuture().whenComplete { _, error -> if (error != null) failed.set(true) }
            } else {
                result
            }
        }
    }

    override fun instrumentExecutionResult(
        executionResult: ExecutionResult,
        parameters: InstrumentationExecutionParameters,
        state: InstrumentationState?
    ): CompletableFuture<ExecutionResult> {
        if (executionResult.errors.isEmpty()) {
            return CompletableFuture.completedFuture(executionResult)
        }
        if (!executionResult.isDataPresent) {
            // request-level errors (syntax, validation, variables): graphql-java's texts, without empty extensions
            return CompletableFuture.completedFuture(
                executionResult.transform { it.errors(executionResult.errors.map { error -> rebuild(error, error.path) }) }
            )
        }
        val first = executionResult.errors.first()
        val path = first.path?.let { path ->
            when {
                // async-graphql reports these two `find_entity` errors without a path
                path == listOf(ENTITIES_FIELD) && first.message in AsyncGraphqlEntitiesDataFetcher.ERRORS_WITHOUT_PATH -> null
                path.size >= 2 && path[0] == ENTITIES_FIELD && path[1] is Number -> listOf(path[0]) + path.drop(2)
                else -> path
            }
        }
        return CompletableFuture.completedFuture(
            executionResult.transform { it.data(null).errors(listOf(rebuild(first, path))) }
        )
    }

    private fun rebuild(error: GraphQLError, path: List<Any>?): GraphQLError =
        GraphqlErrorBuilder.newError()
            .message(error.message)
            .locations(error.locations)
            .path(path)
            .errorType(error.errorType)
            .apply { error.extensions?.takeIf { it.isNotEmpty() }?.let { extensions(it) } }
            .build()

    companion object {
        const val ENTITIES_FIELD = "_entities"
        private const val MUTATION_FAILED_KEY = "org.misarch.compat.mutationFailed"
    }
}

/**
 * `Query._entities` like async-graphql's `QueryRoot` + derived `find_entity`:
 * - representation not an object, unknown `__typename` (incl. unresolvable stubs such as `ProductVariant`) or a key
 *   the resolver cannot use (resolver returns null) -> `Entity not found.`,
 * - `__typename` missing or not a string -> `"__typename" must be an existing string.`,
 * - a resolver error (thrown, or a returned [GraphQLError]) -> its message,
 * - all-or-nothing (`try_join_all`): the first failing representation fails the whole (non-null) field.
 * Representations are resolved in order.
 */
class AsyncGraphqlEntitiesDataFetcher(resolvers: List<FederatedTypeResolver>) : DataFetcher<CompletableFuture<List<Any>>> {

    private val resolversByType = resolvers.associateBy { it.typeName }

    override fun get(environment: DataFetchingEnvironment): CompletableFuture<List<Any>> {
        val representations: List<Any?> = environment.getArgument<List<Any?>>("representations") ?: emptyList()
        val scope = environment.graphQlContext.getOrDefault<CoroutineScope>(
            CoroutineScope::class, CoroutineScope(EmptyCoroutineContext)
        )
        return scope.future {
            representations.map { representation -> resolve(environment, representation) }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun resolve(environment: DataFetchingEnvironment, representation: Any?): Any {
        val params = representation as? Map<String, Any> ?: throw GraphQLErrorException(ENTITY_NOT_FOUND)
        val typeName = params["__typename"] as? String ?: throw GraphQLErrorException(TYPENAME_MISSING)
        val entity = when (val resolver = resolversByType[typeName]) {
            is FederatedTypeSuspendResolver<*> -> resolver.resolve(environment, params)
            is FederatedTypePromiseResolver<*> -> resolver.resolve(environment, params).await()
            else -> null
        }
        // graphql-kotlin's convention: a resolver may return a GraphQLError for a failed representation (order does)
        if (entity is GraphQLError) throw GraphQLErrorException(entity.message)
        return entity ?: throw GraphQLErrorException(ENTITY_NOT_FOUND)
    }

    companion object {
        const val ENTITY_NOT_FOUND = "Entity not found."
        const val TYPENAME_MISSING = "\"__typename\" must be an existing string."

        /** Errors that async-graphql creates without `path` (`ServerError::new(.., Some(pos))` in `find_entity`). */
        val ERRORS_WITHOUT_PATH = setOf(ENTITY_NOT_FOUND, TYPENAME_MISSING)
    }
}
