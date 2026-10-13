package org.misarch.payment.graphql

import graphql.schema.DataFetchingEnvironment
import org.misarch.common.nest.ClassValidator
import org.misarch.common.nest.ClassValidator.Property
import org.misarch.common.nest.ClassValidator.Schema
import org.misarch.common.nest.Js
import org.misarch.payment.nest.queryKeys
import org.misarch.payment.persistence.Match
import tools.jackson.databind.JsonNode

/**
 * The raw GraphQL arguments of a connection field (explicit `null` distinguishable from absent), plus the
 * selection keys of the original's `queryKeys(info)` (computed when the connection is built).
 */
class ConnectionArgs(private val environment: DataFetchingEnvironment) {

    private val arguments: Map<String, Any?> = environment.arguments

    val skip: Long? = (arguments["skip"] as Number?)?.toLong()
    val first: Long? = (arguments["first"] as Number?)?.toLong()

    @Suppress("UNCHECKED_CAST")
    val orderBy: Map<String, Any?>? = arguments["orderBy"] as Map<String, Any?>?

    @Suppress("UNCHECKED_CAST")
    val filter: Map<String, Any?>? = arguments["filter"] as Map<String, Any?>?

    /** `queryKeys(info)` (payment variant: inline fragments throw). */
    val keys: List<String> by lazy { environment.queryKeys() }

    /** `@Min(0) skip`, `@Min(1) first` (ValidationPipe on `FindPaymentArgs` / `FindPaymentInformationsArgs`). */
    fun validate() {
        val node = Js.nodes.objectNode()
        if (arguments.containsKey("skip")) node.set("skip", skip?.let { Js.nodes.numberNode(it) } ?: Js.nodes.nullNode())
        if (arguments.containsKey("first")) node.set("first", first?.let { Js.nodes.numberNode(it) } ?: Js.nodes.nullNode())
        ClassValidator.validateOrThrow(FIND_ARGS, node)
    }

    /** A payment information filter field with Mongo equality semantics. */
    fun filterMatch(name: String): Match {
        val filter = filter ?: return Match.Absent
        if (!filter.containsKey(name)) return Match.Absent
        val value = filter[name] ?: return Match.Null
        return Match.Value(if (value is Enum<*>) value.name else value.toString())
    }

    companion object {
        val FIND_ARGS = Schema(
            listOf(
                Property("skip", listOf(ClassValidator.min(0)), default = Js.nodes.numberNode(0)),
                Property("first", listOf(ClassValidator.min(1)), default = Js.nodes.numberNode(Int.MAX_VALUE)),
            )
        )
    }
}
