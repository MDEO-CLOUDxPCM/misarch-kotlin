package org.misarch.simulation.ecs

import org.misarch.common.ecs.EcsVariable
import org.misarch.common.ecs.VariableDefinition

/**
 * An experiment-config variable of JSON schema type `integer`. A JSON number is converted with Kotlin's `toInt()`
 * (decimals are truncated), a string with `String.toInt()` (`"5abc"` is rejected); other values fail the request.
 */
class IntegerVariable(
    override val name: String,
    defaultValue: Int,
    private val setter: (Int) -> Unit,
) : EcsVariable {

    override val definition = VariableDefinition(schema("integer"), defaultValue)

    override fun set(value: Any) = setter(
        when (value) {
            is Int -> value
            is Number -> value.toDouble().toInt()
            is String -> value.toInt()
            else -> throw IllegalArgumentException("Variable $name must be an integer: $value")
        }
    )
}

/** JSON schema of a variable of the given type */
internal fun schema(type: String) = mapOf("\$schema" to "http://json-schema.org/draft-07/schema#", "type" to type)
