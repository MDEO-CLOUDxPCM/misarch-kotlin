package org.misarch.simulation.ecs

import org.misarch.common.ecs.EcsVariable
import org.misarch.common.ecs.VariableDefinition

/**
 * An experiment-config variable of JSON schema type `number`. A JSON number is converted with `toDouble()`, a string
 * with `String.toDouble()` (`"abc"` is rejected); other values fail the request.
 */
class NumberVariable(
    override val name: String,
    defaultValue: Double,
    private val setter: (Double) -> Unit,
) : EcsVariable {

    override val definition = VariableDefinition(schema("number"), defaultValue)

    override fun set(value: Any) = setter(
        when (value) {
            is Number -> value.toDouble()
            is String -> value.toDouble()
            else -> throw IllegalArgumentException("Variable $name must be a number: $value")
        }
    )
}
