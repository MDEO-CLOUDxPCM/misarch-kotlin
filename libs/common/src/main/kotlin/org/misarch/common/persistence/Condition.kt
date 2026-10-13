package org.misarch.common.persistence

/**
 * A SQL boolean condition with bind values - the replacement for the QueryDSL `BooleanExpression`s of the original
 * services. Values are written as `?` in [sql] and bound as named parameters when the query is rendered, so
 * conditions can be combined freely (`and`/`or`) without parameter-name clashes.
 *
 * Column names are the *unquoted* entity property names (see [MisarchNamingStrategy]): `userId`, `archivedAt`.
 * In queries with joins qualify them with the alias: `t.` is the connection's main table.
 *
 * ```
 * Condition.eq("userId", id) and Condition.isNull("archivedAt")
 * Condition.raw("t.id IN (SELECT productId FROM ProductToCategoryEntity WHERE categoryId = ?)", categoryId)
 * ```
 * A collection value is expanded (`col IN (?)` with a list -> `col IN ($1, $2, ...)`); an empty collection is not
 * valid SQL, use [inList] which handles it. Do not use the jsonb `?` operator in [sql] (use `jsonb_exists`).
 */
class Condition(val sql: String, val values: List<Any?> = emptyList()) {

    init {
        require(sql.count { it == '?' } == values.size) {
            "Condition '$sql' has ${sql.count { it == '?' }} placeholders but ${values.size} values"
        }
    }

    infix fun and(other: Condition): Condition = Condition("($sql) AND (${other.sql})", values + other.values)

    infix fun or(other: Condition): Condition = Condition("($sql) OR (${other.sql})", values + other.values)

    operator fun not(): Condition = Condition("NOT ($sql)", values)

    override fun toString(): String = "Condition($sql, $values)"

    companion object {
        fun eq(column: String, value: Any): Condition = Condition("$column = ?", listOf(value))
        fun ne(column: String, value: Any): Condition = Condition("$column <> ?", listOf(value))
        fun lt(column: String, value: Any): Condition = Condition("$column < ?", listOf(value))
        fun le(column: String, value: Any): Condition = Condition("$column <= ?", listOf(value))
        fun gt(column: String, value: Any): Condition = Condition("$column > ?", listOf(value))
        fun ge(column: String, value: Any): Condition = Condition("$column >= ?", listOf(value))
        fun isNull(column: String): Condition = Condition("$column IS NULL")
        fun isNotNull(column: String): Condition = Condition("$column IS NOT NULL")
        fun inList(column: String, values: Collection<Any>): Condition =
            if (values.isEmpty()) Condition("FALSE") else Condition("$column IN (?)", listOf(values))

        /** Case-insensitive `LIKE '%value%'`. */
        fun containsIgnoreCase(column: String, value: String): Condition =
            Condition("LOWER($column) LIKE ?", listOf("%" + value.lowercase() + "%"))

        fun raw(sql: String, vararg values: Any?): Condition = Condition(sql, values.toList())

        /** AND of all non-null conditions; null if there is none. */
        fun allOf(conditions: Iterable<Condition?>): Condition? =
            conditions.filterNotNull().reduceOrNull { acc, condition -> acc and condition }

        fun allOf(vararg conditions: Condition?): Condition? = allOf(conditions.asIterable())
    }
}

/**
 * Renders conditions into named-parameter SQL (`:p0`, `:p1`, ...) and collects the bind values.
 */
class SqlParameters {
    private val _values = LinkedHashMap<String, Any?>()

    /** The collected values (name -> value). */
    val values: Map<String, Any?> get() = _values

    /** Replaces the `?` placeholders of [condition] by fresh parameter names. */
    fun render(condition: Condition): String {
        val iterator = condition.values.iterator()
        val result = StringBuilder()
        for (c in condition.sql) {
            if (c == '?') {
                val name = "p${_values.size}"
                _values[name] = iterator.next()
                result.append(':').append(name)
            } else {
                result.append(c)
            }
        }
        return result.toString()
    }
}
