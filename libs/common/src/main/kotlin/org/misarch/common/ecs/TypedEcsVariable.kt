package org.misarch.common.ecs

import java.math.BigDecimal

/**
 * An [EcsVariable] that holds its current [value]. This is the way to declare a service's experiment-config variable:
 * create an [IntegerVariable] or [NumberVariable], read [value] where it is used and expose the variable as an
 * `EcsVariable` bean. A value that does not match the declared JSON schema fails the request (500) and the current
 * value stays.
 *
 * @param T type of the value
 * @param defaultValue the default announced in `GET /ecs/defined-variables`
 * @param initialValue the value at start-up (e.g. from an environment variable); `null` means [defaultValue]
 */
abstract class TypedEcsVariable<T : Any>(
    final override val name: String,
    schema: Map<String, Any>,
    defaultValue: T,
    initialValue: T?,
) : EcsVariable {

    final override val definition = VariableDefinition(schema, defaultValue)

    /** The current value; set by the sidecar's request thread, read by any thread. */
    @Volatile
    var value: T = initialValue ?: defaultValue

    final override fun set(value: Any) {
        this.value = convert(value)
    }

    /** Converts a JSON-decoded value; throws [IllegalArgumentException] if it does not match the schema. */
    protected abstract fun convert(value: Any): T
}

/**
 * An experiment-config variable of JSON schema type `integer`: accepts a JSON number without fractional part (`5`,
 * `5.0`) in the `Int` range and not below [minimum].
 *
 * @param minimum the schema's `minimum`, if any
 */
class IntegerVariable(
    name: String,
    defaultValue: Int,
    initialValue: Int? = null,
    private val minimum: Int? = null,
) : TypedEcsVariable<Int>(name, jsonSchema("integer", minimum), defaultValue, initialValue) {

    override fun convert(value: Any): Int {
        val number = (value as? Number)?.let { runCatching { BigDecimal(it.toString()).intValueExact() }.getOrNull() }
        require(number != null && (minimum == null || number >= minimum)) {
            "Variable $name must be an integer${minimum?.let { " >= $it" } ?: ""}: $value"
        }
        return number
    }
}

/**
 * An experiment-config variable of JSON schema type `number`: accepts any JSON number.
 */
class NumberVariable(
    name: String,
    defaultValue: Double,
    initialValue: Double? = null,
) : TypedEcsVariable<Double>(name, jsonSchema("number"), defaultValue, initialValue) {

    override fun convert(value: Any): Double {
        require(value is Number) { "Variable $name must be a number: $value" }
        return value.toDouble()
    }
}

/** JSON schema (draft-07) of a variable: `$schema`, `type` and the optional `minimum`, in this order. */
private fun jsonSchema(type: String, minimum: Int? = null): Map<String, Any> = buildMap {
    put("\$schema", "http://json-schema.org/draft-07/schema#")
    put("type", type)
    minimum?.let { put("minimum", it) }
}
