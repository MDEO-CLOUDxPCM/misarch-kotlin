package org.misarch.common.rust

import com.expediagroup.graphql.generator.extensions.plus
import com.expediagroup.graphql.generator.federation.execution.FederatedTypePromiseResolver
import com.expediagroup.graphql.generator.federation.execution.FederatedTypeResolver
import com.expediagroup.graphql.generator.federation.execution.FederatedTypeSuspendResolver
import com.expediagroup.graphql.server.spring.execution.DefaultSpringGraphQLContextFactory
import com.expediagroup.graphql.server.spring.execution.SpringGraphQLRequestParser
import com.expediagroup.graphql.server.types.GraphQLBatchRequest
import com.expediagroup.graphql.server.types.GraphQLRequest
import com.expediagroup.graphql.server.types.GraphQLServerRequest
import graphql.GraphQLContext
import graphql.execution.DataFetcherExceptionHandlerParameters
import graphql.execution.DataFetcherExceptionHandlerResult
import graphql.schema.DataFetcher
import graphql.schema.DataFetchingEnvironment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.future.await
import kotlinx.coroutines.future.future
import kotlinx.coroutines.reactor.awaitSingle
import org.misarch.common.graphql.AuthorizedUser
import org.misarch.common.graphql.GraphQLErrorException
import org.misarch.common.graphql.MisarchDataFetcherExceptionHandler
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.http.MediaType
import org.springframework.http.codec.multipart.FilePart
import org.springframework.http.codec.multipart.FormFieldPart
import org.springframework.web.reactive.function.server.ServerRequest
import tools.jackson.databind.ObjectMapper
import java.util.concurrent.CompletableFuture

/**
 * Marks a Rust `panic!`/`unwrap()` failure inside a resolver. The original aborts the HTTP connection without a
 * response (Dapr then answers the caller with HTTP 500); [RustCompatConfiguration]'s `/` route answers **500 with
 * an empty body** for the whole request instead.
 */
class RustPanic(message: String) : RuntimeException(message)

/** Per-request flag set when a resolver threw [RustPanic] (exchange attribute + GraphQL context entry). */
class RustPanicFlag {
    @Volatile
    var panicked: Boolean = false

    companion object {
        const val ATTRIBUTE = "org.misarch.common.rust.RustPanicFlag"
    }
}

/** A file of a GraphQL multipart request (async-graphql `UploadValue`). */
class RustUpload(
    val filename: String,
    /** The part's `Content-Type` as parsed by `mime` (lower-cased), null if absent or unparsable (multer). */
    val contentType: String?,
    val content: ByteArray,
)

/**
 * Context factory of the Rust ports: `Authorized-User` parsed leniently ([RustAuthorization.parseHeader]: invalid →
 * absent, no error) and the [RustPanicFlag] of the request.
 */
open class RustGraphQLContextFactory : DefaultSpringGraphQLContextFactory() {
    override suspend fun generateContext(request: ServerRequest): GraphQLContext {
        val additional = mutableMapOf<Any, Any>()
        RustAuthorization.parseHeader(request.headers().firstHeader(AuthorizedUser.HEADER_NAME))
            ?.let { additional[AuthorizedUser::class] = it }
        (request.exchange().attributes[RustPanicFlag.ATTRIBUTE] as? RustPanicFlag)
            ?.let { additional[RustPanicFlag::class] = it }
        return super.generateContext(request) + additional
    }
}

/** [MisarchDataFetcherExceptionHandler] that also records [RustPanic]s in the request's [RustPanicFlag]. */
open class RustDataFetcherExceptionHandler : MisarchDataFetcherExceptionHandler() {
    override fun handleException(
        handlerParameters: DataFetcherExceptionHandlerParameters
    ): CompletableFuture<DataFetcherExceptionHandlerResult> {
        if (generateSequence(handlerParameters.exception) { it.cause }.take(10).any { it is RustPanic }) {
            handlerParameters.dataFetchingEnvironment?.graphQlContext
                ?.getOrEmpty<RustPanicFlag>(RustPanicFlag::class)?.ifPresent { it.panicked = true }
        }
        return super.handleException(handlerParameters)
    }
}

/**
 * `_entities` with async-graphql semantics (replaces graphql-kotlin's partial-result fetcher):
 * representations are resolved concurrently; a resolver returning null gives a `null` element; **any** failure fails
 * the whole field (`data: null`): a non-object representation or unknown `__typename` → `Entity not found.`, a missing
 * or non-string `__typename` → `"__typename" must be an existing string.`, a resolver exception → its error.
 * Resolvers throw [entityNotFound] for a missing/unparsable key.
 */
class RustEntitiesDataFetcher(resolvers: List<FederatedTypeResolver>) : DataFetcher<CompletableFuture<List<Any?>>> {

    private val byType = resolvers.associateBy { it.typeName }

    override fun get(env: DataFetchingEnvironment): CompletableFuture<List<Any?>> {
        val representations: List<Any?> = env.getArgument<List<Any?>>("representations") ?: emptyList()
        val scope = env.graphQlContext.getOrEmpty<CoroutineScope>(CoroutineScope::class).orElse(null)
        @Suppress("OPT_IN_USAGE")
        return (scope ?: GlobalScope).future {
            representations.map { representation -> async { resolve(env, representation) } }.awaitAll()
        }
    }

    private suspend fun resolve(env: DataFetchingEnvironment, representation: Any?): Any? {
        @Suppress("UNCHECKED_CAST")
        val params = representation as? Map<String, Any> ?: throw entityNotFound()
        val typeName = params["__typename"] as? String
            ?: throw GraphQLErrorException("\"__typename\" must be an existing string.")
        return when (val resolver = byType[typeName]) {
            is FederatedTypeSuspendResolver<*> -> resolver.resolve(env, params)
            is FederatedTypePromiseResolver<*> -> resolver.resolve(env, params).await()
            null -> throw entityNotFound()
        }
    }

    companion object {
        /** async-graphql's error when no entity resolver matches (unknown typename, missing/unparsable key). */
        fun entityNotFound() = GraphQLErrorException("Entity not found.")
    }
}

/**
 * Request parsing of async-graphql-axum's `GraphQLRequest` extractor: single JSON requests, GraphQL multipart
 * requests (`operations`, `map`, file parts → [RustUpload] values in the variables); a batch (JSON array) is rejected
 * (→ HTTP 400, `UnsupportedBatch`).
 */
open class RustGraphQLRequestParser(private val objectMapper: ObjectMapper) : SpringGraphQLRequestParser(objectMapper) {

    override suspend fun parseRequest(request: ServerRequest): GraphQLServerRequest? {
        val contentType = request.headers().contentType().orElse(null)
        val parsed = if (contentType != null && MediaType.MULTIPART_FORM_DATA.includes(contentType)) {
            parseMultipart(request)
        } else {
            super.parseRequest(request)
        }
        if (parsed is GraphQLBatchRequest) throw IllegalArgumentException("UnsupportedBatch")
        return parsed
    }

    private suspend fun parseMultipart(request: ServerRequest): GraphQLServerRequest {
        val parts = request.multipartData().awaitSingle()
        val operations = (parts.getFirst("operations") as? FormFieldPart)?.value()
            ?: throw IllegalArgumentException("MissingOperatorsPart")
        val map = (parts.getFirst("map") as? FormFieldPart)?.value()
            ?: throw IllegalArgumentException("MissingMapPart")
        val graphQLRequest = objectMapper.readValue(operations, GraphQLServerRequest::class.java) as? GraphQLRequest
            ?: throw IllegalArgumentException("UnsupportedBatch")
        @Suppress("UNCHECKED_CAST")
        val fileMap = objectMapper.readValue(map, Map::class.java) as Map<String, List<String>>
        val variables: MutableMap<String, Any?> = graphQLRequest.variables?.toMutableMap() ?: mutableMapOf()
        for ((name, paths) in fileMap) {
            val file = parts.getFirst(name) as? FilePart ?: continue
            val bytes = DataBufferUtils.join(file.content()).awaitSingle().let { buffer ->
                ByteArray(buffer.readableByteCount()).also { buffer.read(it); DataBufferUtils.release(buffer) }
            }
            val rawContentType = file.headers().getFirst("Content-Type")
            val upload = RustUpload(
                filename = file.filename(),
                contentType = rawContentType?.takeIf { RustMime.parse(it) != null }?.lowercase(),
                content = bytes,
            )
            for (path in paths) setVariable(variables, path, upload)
        }
        return GraphQLRequest(graphQLRequest.query, graphQLRequest.operationName, variables, graphQLRequest.extensions)
    }

    /** Sets `variables.a.b.0` (async-graphql `Request::set_upload`). */
    @Suppress("UNCHECKED_CAST")
    private fun setVariable(variables: MutableMap<String, Any?>, path: String, value: Any) {
        val segments = path.split('.')
        if (segments.size < 2 || segments[0] != "variables") return
        var current: Any? = variables
        for ((i, segment) in segments.drop(1).withIndex()) {
            val last = i == segments.size - 2
            when (current) {
                is MutableMap<*, *> -> {
                    val map = current as MutableMap<String, Any?>
                    if (last) map[segment] = value else {
                        val next = map[segment]
                        current = when (next) {
                            is Map<*, *> -> (next as Map<String, Any?>).toMutableMap().also { map[segment] = it }
                            is List<*> -> next.toMutableList().also { map[segment] = it }
                            else -> return
                        }
                    }
                }
                is MutableList<*> -> {
                    val list = current as MutableList<Any?>
                    val index = segment.toIntOrNull() ?: return
                    if (index !in list.indices) return
                    if (last) list[index] = value else {
                        val next = list[index]
                        current = when (next) {
                            is Map<*, *> -> (next as Map<String, Any?>).toMutableMap().also { list[index] = it }
                            is List<*> -> next.toMutableList().also { list[index] = it }
                            else -> return
                        }
                    }
                }
                else -> return
            }
        }
    }
}

/** async-graphql argument coercion for unsigned Rust integer types (`u32`, `u64`, `usize`) mapped to `Int`. */
object RustArgs {
    /** A negative value fails like async-graphql (`n.as_u64()` is None): `Failed to parse "Int": Invalid number`. */
    fun unsigned(value: Int?): Long? {
        if (value != null && value < 0) throw GraphQLErrorException("Failed to parse \"Int\": Invalid number")
        return value?.toLong()
    }
}
