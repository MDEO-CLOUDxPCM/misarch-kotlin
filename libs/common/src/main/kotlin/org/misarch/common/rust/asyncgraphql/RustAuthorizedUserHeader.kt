package org.misarch.common.rust.asyncgraphql

import com.expediagroup.graphql.generator.extensions.plus
import com.expediagroup.graphql.server.spring.execution.DefaultSpringGraphQLContextFactory
import graphql.GraphQLContext
import org.misarch.common.graphql.AuthorizedUser
import org.misarch.common.rust.RustUuid
import org.springframework.web.reactive.function.server.ServerRequest
import tools.jackson.core.JacksonException
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/**
 * The `Authorized-User` header as the Rust originals read it (`authorization.rs`): serde struct
 * `{id: Uuid, roles: Vec<Role>}` with `Role` in `buyer | admin | employee` (snake_case, case-sensitive). Both fields
 * are required, unknown fields are ignored. Any failure (no header, non-visible-ASCII value, invalid JSON, missing
 * field, unknown role, invalid uuid) means "no header" - the request is still executed and resolvers that need the
 * user fail (`dfe.rustAuthorizeUser(id)`, [org.misarch.common.rust.RustAuthorization.authorize]).
 *
 * Not merged with [org.misarch.common.rust.RustAuthorization.parseHeader] (review, invoice, media): that one rejects
 * duplicate JSON keys (here: the last value wins) and parses the id with [RustUuid.parseOrNull] (here:
 * [RustUuid.tryParse]).
 */
object RustAuthorizedUserHeader {

    private val ROLES = setOf("buyer", "admin", "employee")

    private val mapper: JsonMapper = JsonMapper.builder()
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .build()

    /**
     * Parses the header value; null if the original would not accept it.
     */
    fun parse(value: String): AuthorizedUser? {
        // http::HeaderValue::to_str: visible ASCII (and tab) only
        if (value.any { it != '\t' && (it < ' ' || it > '~') }) {
            return null
        }
        val node: JsonNode = try {
            mapper.readTree(value)
        } catch (e: JacksonException) {
            return null
        } ?: return null
        if (!node.isObject) {
            return null
        }
        val idNode = node.get("id") ?: return null
        val rolesNode = node.get("roles") ?: return null
        if (!idNode.isString || !rolesNode.isArray) {
            return null
        }
        val id = RustUuid.tryParse(idNode.asString()) ?: return null
        val roles = mutableListOf<String>()
        for (role in rolesNode) {
            if (!role.isString || role.asString() !in ROLES) {
                return null
            }
            roles += role.asString()
        }
        return AuthorizedUser(id, roles)
    }
}

/**
 * Puts the [AuthorizedUser] parsed with the Rust rules ([RustAuthorizedUserHeader]) into the GraphQL context under
 * the key `AuthorizedUser::class` (as libs/common does), but never fails the request for an unparsable header.
 */
class RustAuthorizedUserGraphQLContextFactory : DefaultSpringGraphQLContextFactory() {

    override suspend fun generateContext(request: ServerRequest): GraphQLContext {
        val user = request.headers().firstHeader(AuthorizedUser.HEADER_NAME)?.let { RustAuthorizedUserHeader.parse(it) }
        val context = super.generateContext(request)
        return if (user != null) context + mapOf<Any, Any>(AuthorizedUser::class to user) else context
    }
}
