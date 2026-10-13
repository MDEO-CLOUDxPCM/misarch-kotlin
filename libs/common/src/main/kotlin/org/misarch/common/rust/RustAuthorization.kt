package org.misarch.common.rust

import graphql.schema.DataFetchingEnvironment
import org.misarch.common.graphql.AuthorizedUser
import org.misarch.common.graphql.GraphQLErrorException
import org.misarch.common.graphql.authorizedUserOrNull
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/**
 * `src/authorization.rs` of the Rust originals (identical in all of them).
 *
 * Header parsing (`AuthorizedUserHeader::try_from`): serde `{"id": <uuid>, "roles": [<"buyer"|"admin"|"employee">]}`;
 * extra keys are ignored. Anything else - invalid JSON, a missing/`null` field, a bad uuid, an unknown role,
 * a non-visible-ASCII header value - makes the header count as **absent** (no error by itself).
 */
object RustAuthorization {

    const val NOT_SET = "Authentication failed. Authorized-User header is not set or could not be parsed."

    private val ROLES = setOf("buyer", "admin", "employee")
    private val PERMISSIVE = setOf("admin", "employee")

    private val mapper: JsonMapper = JsonMapper.builder()
        .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .build()

    /** Parses the `Authorized-User` header value like the Rust services; null = treated as absent. */
    fun parseHeader(value: String?): AuthorizedUser? {
        if (value == null) return null
        // axum HeaderValue::to_str: visible ASCII and tab only
        if (value.any { it != '\t' && (it.code < 0x20 || it.code > 0x7E) }) return null
        val root: JsonNode = try {
            mapper.readTree(value)
        } catch (e: RuntimeException) {
            return null
        } ?: return null
        if (!root.isObject) return null
        val idNode = root.get("id")
        val rolesNode = root.get("roles")
        if (idNode == null || !idNode.isString || rolesNode == null || !rolesNode.isArray) return null
        val id = RustUuid.parseOrNull(idNode.stringValue()) ?: return null
        val roles = mutableListOf<String>()
        for (role in rolesNode) {
            if (!role.isString || role.stringValue() !in ROLES) return null
            roles += role.stringValue()
        }
        return AuthorizedUser(id, roles)
    }

    /**
     * `authorize_user(ctx, id)`: OK if the user has a permissive role (admin, employee) or `id` equals the header id.
     *
     * @throws GraphQLErrorException with the original messages
     */
    fun authorize(user: AuthorizedUser?, id: UUID?) {
        if (user == null) throw GraphQLErrorException(NOT_SET)
        if (user.roles.any { it in PERMISSIVE } || (id != null && user.id == id)) return
        throw GraphQLErrorException("Authentication failed for user of UUID: `${user.id}`. Operation not permitted.")
    }
}

/** `authorize_user(&ctx, id)` of the Rust originals for the current request. */
fun DataFetchingEnvironment.rustAuthorizeUser(id: UUID?) = RustAuthorization.authorize(authorizedUserOrNull, id)
