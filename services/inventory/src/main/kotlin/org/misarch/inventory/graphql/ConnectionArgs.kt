package org.misarch.inventory.graphql

import graphql.schema.DataFetchingEnvironment
import org.misarch.common.nest.ClassValidator
import org.misarch.common.nest.ClassValidator.Property
import org.misarch.common.nest.ClassValidator.Schema
import org.misarch.common.nest.Js
import org.misarch.inventory.persistence.FilterValue
import tools.jackson.databind.JsonNode

/**
 * The raw GraphQL arguments of a connection field, read from graphql-java's coerced argument map so that an
 * explicit `null` is distinguishable from an absent value (the original passes the argument objects untouched to
 * Mongo, where `{field: null}` matches null/missing).
 */
class ConnectionArgs(environment: DataFetchingEnvironment) {

    private val arguments: Map<String, Any?> = environment.arguments

    /** `skip`/`first` after the GraphQL defaults (0 / 2147483647); null = explicit null. */
    val skip: Long? = (arguments["skip"] as Number?)?.toLong()
    val first: Long? = (arguments["first"] as Number?)?.toLong()

    @Suppress("UNCHECKED_CAST")
    val orderBy: Map<String, Any?>? = arguments["orderBy"] as Map<String, Any?>?

    @Suppress("UNCHECKED_CAST")
    val filter: Map<String, Any?>? = arguments["filter"] as Map<String, Any?>?

    /** `@Min(0) skip`, `@Min(1) first` of `FindProductItemsArgs` (ValidationPipe). */
    fun validate() {
        val node = Js.nodes.objectNode()
        if (arguments.containsKey("skip")) node.set("skip", skip?.let { Js.nodes.numberNode(it) } ?: Js.nodes.nullNode())
        if (arguments.containsKey("first")) node.set("first", first?.let { Js.nodes.numberNode(it) } ?: Js.nodes.nullNode())
        ClassValidator.validateOrThrow(FIND_ARGS, node)
    }

    /** The default order direction is ascending; ProductItemOrderField has only `ID` (`_id`). */
    val ascending: Boolean get() = enumName(orderBy?.get("direction")) != "DESC"

    /** A filter field as `countDocuments` sees it. */
    fun filterValue(name: String): FilterValue {
        val filter = filter ?: return FilterValue.Absent
        if (!filter.containsKey(name)) return FilterValue.Absent
        val value = filter[name] ?: return FilterValue.Null
        return FilterValue.Value(enumName(value) ?: value.toString())
    }

    /** JS truthiness of a filter field (`if (filter.x)`). */
    fun filterTruthy(name: String): Boolean = filter?.get(name)?.toString()?.isNotEmpty() == true

    companion object {
        val FIND_ARGS = Schema(
            listOf(
                Property("skip", listOf(ClassValidator.min(0)), default = Js.nodes.numberNode(0)),
                Property("first", listOf(ClassValidator.min(1)), default = Js.nodes.numberNode(Int.MAX_VALUE)),
            )
        )

        fun enumName(value: Any?): String? = when (value) {
            null -> null
            is Enum<*> -> value.name
            else -> value.toString()
        }
    }
}
