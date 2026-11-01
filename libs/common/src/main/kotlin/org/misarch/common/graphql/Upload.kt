package org.misarch.common.graphql

import com.expediagroup.graphql.server.spring.execution.SpringGraphQLRequestParser
import com.expediagroup.graphql.server.types.GraphQLRequest
import com.expediagroup.graphql.server.types.GraphQLServerRequest
import graphql.GraphQLContext
import graphql.execution.CoercedVariables
import graphql.language.Value
import graphql.schema.Coercing
import graphql.schema.CoercingParseLiteralException
import graphql.schema.CoercingParseValueException
import graphql.schema.CoercingSerializeException
import graphql.schema.GraphQLScalarType
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.http.MediaType
import org.springframework.http.codec.multipart.FilePart
import org.springframework.http.codec.multipart.FormFieldPart
import org.springframework.web.reactive.function.server.ServerRequest
import tools.jackson.databind.ObjectMapper
import java.util.Locale

/**
 * A file of a GraphQL multipart request (https://github.com/jaydenseric/graphql-multipart-request-spec), the value
 * of the `Upload` scalar ([SCALAR], registered by default). Requires `misarch.graphql.multipart-upload: true`
 * (then [MultipartGraphQLRequestParser] parses `multipart/form-data` requests).
 *
 * @property filename the part's file name
 * @property contentType the part's `Content-Type` (normalised), null if absent or not a valid media type
 * @property content the file content
 */
class Upload(
    val filename: String,
    val contentType: String?,
    val content: ByteArray,
) {
    companion object {
        /** The input-only `Upload` scalar: accepts only an [Upload] placed into the variables by the parser. */
        val SCALAR: GraphQLScalarType = GraphQLScalarType.newScalar()
            .name("Upload")
            .coercing(object : Coercing<Upload, Nothing> {
                override fun serialize(dataFetcherResult: Any, graphQLContext: GraphQLContext, locale: Locale): Nothing =
                    throw CoercingSerializeException("Upload is an input-only scalar")

                override fun parseValue(input: Any, graphQLContext: GraphQLContext, locale: Locale): Upload =
                    input as? Upload ?: throw CoercingParseValueException("Invalid upload value")

                override fun parseLiteral(
                    input: Value<*>, variables: CoercedVariables, graphQLContext: GraphQLContext, locale: Locale
                ): Upload = throw CoercingParseLiteralException("Invalid upload value")
            })
            .build()
    }
}

/**
 * Request parser that additionally accepts GraphQL multipart requests: the `operations` part (one request), the
 * `map` part (`{"0": ["variables.file"]}`) and one file part per map entry; each file becomes an [Upload] at its
 * variable path. Other requests are parsed by graphql-kotlin's [SpringGraphQLRequestParser].
 */
open class MultipartGraphQLRequestParser(private val objectMapper: ObjectMapper) : SpringGraphQLRequestParser(objectMapper) {

    override suspend fun parseRequest(request: ServerRequest): GraphQLServerRequest? {
        val contentType = request.headers().contentType().orElse(null)
        return if (contentType != null && MediaType.MULTIPART_FORM_DATA.includes(contentType)) {
            parseMultipart(request)
        } else {
            super.parseRequest(request)
        }
    }

    private suspend fun parseMultipart(request: ServerRequest): GraphQLServerRequest {
        val parts = request.multipartData().awaitSingle()
        val operations = requireNotNull((parts.getFirst("operations") as? FormFieldPart)?.value()) {
            "Missing multipart field 'operations'"
        }
        val map = requireNotNull((parts.getFirst("map") as? FormFieldPart)?.value()) {
            "Missing multipart field 'map'"
        }
        val graphQLRequest = requireNotNull(
            objectMapper.readValue(operations, GraphQLServerRequest::class.java) as? GraphQLRequest
        ) { "Batched multipart requests are not supported" }
        @Suppress("UNCHECKED_CAST")
        val fileMap = objectMapper.readValue(map, Map::class.java) as Map<String, List<String>>
        val variables: MutableMap<String, Any?> = graphQLRequest.variables?.toMutableMap() ?: mutableMapOf()
        for ((name, paths) in fileMap) {
            val file = parts.getFirst(name) as? FilePart ?: continue
            val content = DataBufferUtils.join(file.content()).awaitSingle().let { buffer ->
                ByteArray(buffer.readableByteCount()).also { buffer.read(it); DataBufferUtils.release(buffer) }
            }
            val contentType = file.headers().getFirst("Content-Type")
                ?.let { runCatching { MediaType.parseMediaType(it).toString() }.getOrNull() }
            val upload = Upload(file.filename(), contentType, content)
            for (path in paths) setVariable(variables, path, upload)
        }
        return GraphQLRequest(graphQLRequest.query, graphQLRequest.operationName, variables, graphQLRequest.extensions)
    }

    /** Sets the value at `variables.a.b.0` (paths into maps and lists; unknown paths are ignored). */
    @Suppress("UNCHECKED_CAST")
    private fun setVariable(variables: MutableMap<String, Any?>, path: String, value: Any) {
        val segments = path.split('.')
        if (segments.size < 2 || segments[0] != "variables") return
        var current: Any? = variables
        for ((i, segment) in segments.drop(1).withIndex()) {
            val last = i == segments.size - 2
            val next: Any? = when (current) {
                is MutableMap<*, *> -> {
                    val container = current as MutableMap<String, Any?>
                    if (last) {
                        container[segment] = value
                        return
                    }
                    copy(container[segment])?.also { container[segment] = it }
                }
                is MutableList<*> -> {
                    val container = current as MutableList<Any?>
                    val index = segment.toIntOrNull()?.takeIf { it in container.indices } ?: return
                    if (last) {
                        container[index] = value
                        return
                    }
                    copy(container[index])?.also { container[index] = it }
                }
                else -> null
            }
            current = next ?: return
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun copy(value: Any?): Any? = when (value) {
        is Map<*, *> -> (value as Map<String, Any?>).toMutableMap()
        is List<*> -> value.toMutableList()
        else -> null
    }
}
