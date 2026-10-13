package org.misarch.common.nest

import com.expediagroup.graphql.generator.extensions.plus
import com.expediagroup.graphql.server.spring.execution.DefaultSpringGraphQLContextFactory
import graphql.GraphQLContext
import graphql.schema.DataFetchingEnvironment
import org.springframework.web.reactive.function.server.ServerRequest
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/** The raw `Authorized-User` header of a GraphQL request (null if absent). */
data class RawAuthorizedUser(val header: String?)

/**
 * GraphQL context factory: keeps the raw `Authorized-User` header, because the original parses it lazily (per
 * guarded resolver) with `JSON.parse` and its errors appear as field errors.
 */
class RawAuthorizedUserContextFactory : DefaultSpringGraphQLContextFactory() {
    override suspend fun generateContext(request: ServerRequest): GraphQLContext {
        val header = request.headers().firstHeader("Authorized-User")
        return super.generateContext(request) + mapOf(RawAuthorizedUser::class to RawAuthorizedUser(header))
    }
}

/**
 * The global `RolesGuard` of the original, applied to every resolver that carries `@Roles(...)`:
 * 1. header missing or empty → `ForbiddenException("Forbidden resource")`,
 * 2. `JSON.parse(header)` (errors propagate with V8's message),
 * 3. allowed iff `requiredRoles.some(role => roles.includes(role))` with `roles = parsed.roles` — array membership,
 *    substring test for a string, `TypeError` for anything else.
 */
object RolesGuard {

    const val ADMIN = "admin"
    const val BUYER = "buyer"
    const val EMPLOYEE = "employee"

    private val mapper = JsonMapper.builder().build()

    /** Runs the guard; returns the parsed header (`req.user` in the original). */
    fun check(environment: DataFetchingEnvironment, vararg requiredRoles: String): JsonNode {
        val raw = environment.graphQlContext.get<RawAuthorizedUser>(RawAuthorizedUser::class)?.header
        return check(raw, *requiredRoles)
    }

    fun check(header: String?, vararg requiredRoles: String): JsonNode {
        if (header.isNullOrEmpty()) throw ForbiddenException()
        val user = jsonParse(header)
        val roles = if (user.isObject) user.get("roles") else null
        val allowed = requiredRoles.any { role -> includes(roles, user, role) }
        if (!allowed) throw ForbiddenException()
        return user
    }

    private fun includes(roles: JsonNode?, user: JsonNode, role: String): Boolean = when {
        // `parsed.roles` on null/undefined itself
        user.isNull -> throw JsError("Cannot read properties of null (reading 'roles')")
        roles == null || roles.isMissingNode -> throw JsError("Cannot read properties of undefined (reading 'includes')")
        roles.isNull -> throw JsError("Cannot read properties of null (reading 'includes')")
        roles.isArray -> roles.any { it.isString && it.asString() == role }
        roles.isString -> roles.asString().contains(role)
        else -> throw JsError("roles.includes is not a function")
    }

    /** `JSON.parse` with V8-like error messages for the common cases. */
    fun jsonParse(text: String): JsonNode {
        try {
            val parser = mapper.createParser(text)
            parser.use {
                val node: JsonNode = mapper.readTree(it) ?: throw JsError("Unexpected end of JSON input")
                if (it.nextToken() != null) {
                    throw JsError(unexpectedToken(text, it.currentTokenLocation().charOffset.toInt()))
                }
                return node
            }
        } catch (e: JsError) {
            throw e
        } catch (e: JacksonException) {
            val offset = e.location?.charOffset?.toInt() ?: -1
            if (offset < 0 || offset >= text.trimEnd().length) throw JsError("Unexpected end of JSON input")
            throw JsError(unexpectedToken(text, offset))
        }
    }

    private fun unexpectedToken(text: String, offset: Int): String {
        // Jackson reports the offset after the offending character for unrecognized tokens; V8 the offending one
        val position = firstInvalidPosition(text) ?: offset
        return if (position >= text.length) "Unexpected end of JSON input"
        else "Unexpected token ${text[position]} in JSON at position $position"
    }

    /** Position of the first character that cannot start/continue a JSON value at top level (simple scan). */
    private fun firstInvalidPosition(text: String): Int? {
        var i = 0
        while (i < text.length && text[i].isWhitespace()) i++
        if (i >= text.length) return null
        val c = text[i]
        return when {
            c == '{' || c == '[' || c == '"' || c == '-' || c.isDigit() -> null
            text.startsWith("true", i) || text.startsWith("false", i) || text.startsWith("null", i) -> null
            c == 't' || c == 'f' || c == 'n' -> {
                val literal = when (c) { 't' -> "true"; 'f' -> "false"; else -> "null" }
                var k = 0
                while (i + k < text.length && k < literal.length && text[i + k] == literal[k]) k++
                i + k
            }
            else -> i
        }
    }
}
