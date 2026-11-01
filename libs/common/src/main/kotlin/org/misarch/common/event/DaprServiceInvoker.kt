package org.misarch.common.event

import kotlinx.coroutines.reactor.awaitSingle
import org.misarch.common.graphql.AuthorizedUser
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.client.WebClient
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.net.URI

/**
 * Synchronous service invocation through the Dapr sidecar's HTTP API:
 * `{httpEndpoint}/v1.0/invoke/{appId}/method/{method}` (the way services call
 * other subgraphs).
 */
open class DaprServiceInvoker(
    val webClient: WebClient,
    private val properties: DaprProperties,
    private val objectMapper: ObjectMapper,
) {

    /** The invocation URI of [method] (path without leading slash, may be empty) of the app [appId]. */
    fun uri(appId: String, method: String): URI =
        URI.create("${properties.httpEndpoint.trimEnd('/')}/v1.0/invoke/$appId/method/${method.trimStart('/')}")

    /**
     * Executes a GraphQL operation at [appId] (at the path [method]: `graphql` by default, `""` for a
     * subgraph served at `/`) and returns
     * the whole response document (`data`, `errors`). The `Authorized-User` header is forwarded if [authorizedUser]
     * is given.
     */
    suspend fun graphql(
        appId: String,
        query: String,
        variables: Map<String, Any?> = emptyMap(),
        authorizedUser: AuthorizedUser? = null,
        method: String = "graphql",
    ): JsonNode {
        val body = mapOf("query" to query, "variables" to variables)
        val request = webClient.post()
            .uri(uri(appId, method))
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON)
        if (authorizedUser != null) {
            request.header(AuthorizedUser.HEADER_NAME, objectMapper.writeValueAsString(authorizedUser))
        }
        val response = request
            .bodyValue(objectMapper.writeValueAsBytes(body))
            .retrieve()
            .bodyToMono(ByteArray::class.java)
            .awaitSingle()
        return objectMapper.readTree(response)
    }
}
