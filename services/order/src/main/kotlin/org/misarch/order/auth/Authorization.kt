package org.misarch.order.auth

import com.expediagroup.graphql.generator.extensions.plus
import com.expediagroup.graphql.server.spring.execution.DefaultSpringGraphQLContextFactory
import com.expediagroup.graphql.server.spring.execution.SpringGraphQLContextFactory
import graphql.GraphQLContext
import graphql.schema.DataFetchingEnvironment
import org.misarch.common.graphql.AuthorizedUser
import org.misarch.common.graphql.authorizedUserOrNull
import org.misarch.order.remote.elementList
import org.misarch.order.rust.RustError
import org.misarch.order.rust.RustMessages
import org.misarch.common.rust.RustUuid
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.reactive.function.server.ServerRequest
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/**
 * `Authorized-User` header handling of the Rust original (`authorization.rs`):
 * the header must be JSON `{"id": <uuid>, "roles": [<role>...]}` with roles out of `buyer`, `admin`, `employee`
 * (serde enum, exact lower case); unknown keys are ignored. Anything else (missing header, invalid JSON, unknown
 * role, missing `roles`) is treated as "no header" - the request still executes and resolvers that authorize fail.
 */
object AuthorizedUserHeader {
    private val mapper = JsonMapper.builder().build()
    private val ROLES = setOf("buyer", "admin", "employee")

    /** Parses [value] like serde would, null if it does not parse. */
    fun parse(value: String?): AuthorizedUser? {
        if (value == null) return null
        val node: JsonNode = try {
            mapper.readTree(value)
        } catch (e: Exception) {
            return null
        } ?: return null
        if (!node.isObject) return null
        val idNode = node.get("id") ?: return null
        val rolesNode = node.get("roles") ?: return null
        if (!idNode.isString || !rolesNode.isArray) return null
        val id = RustUuid.tryParse(idNode.asString()) ?: return null
        val roles = ArrayList<String>()
        for (role in rolesNode.elementList()) {
            if (!role.isString || role.asString() !in ROLES) return null
            roles += role.asString()
        }
        return AuthorizedUser(id, roles)
    }

    /** serde serialization of the parsed header (`{"id":"…","roles":[…]}`), forwarded to the shoppingcart. */
    fun serialize(user: AuthorizedUser): String =
        mapper.writeValueAsString(linkedMapOf("id" to user.id.toString(), "roles" to user.roles))
}

/**
 * `authorize_user(ctx, Some(id))`: permitted if the header has a permissive role (`admin`, `employee`) or its id
 * equals [id].
 */
fun DataFetchingEnvironment.authorizeUser(id: java.util.UUID) {
    val user = authorizedUserOrNull ?: throw RustError(RustMessages.AUTHENTICATION_MISSING)
    val permissive = user.roles.any { it == "admin" || it == "employee" }
    if (!permissive && user.id != id) {
        throw RustError(RustMessages.notPermitted(user.id))
    }
}

/**
 * Replaces the shared context factory: puts the [AuthorizedUser] parsed with the Rust rules into the GraphQL context
 * (key `AuthorizedUser::class`, so `dfe.authorizedUserOrNull` works); an unparsable header does not fail the request.
 */
class OrderGraphQLContextFactory : DefaultSpringGraphQLContextFactory() {
    override suspend fun generateContext(request: ServerRequest): GraphQLContext {
        val user = AuthorizedUserHeader.parse(request.headers().firstHeader(AuthorizedUser.HEADER_NAME))
        val additional: Map<Any, Any> = if (user != null) mapOf(AuthorizedUser::class to user) else emptyMap()
        return super.generateContext(request) + additional
    }
}

@Configuration(proxyBeanMethods = false)
class AuthorizationConfiguration {
    @Bean
    fun orderGraphQLContextFactory(): SpringGraphQLContextFactory = OrderGraphQLContextFactory()
}
