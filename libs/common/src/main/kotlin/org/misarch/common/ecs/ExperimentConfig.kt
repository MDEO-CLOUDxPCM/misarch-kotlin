package org.misarch.common.ecs

import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.context.annotation.Bean
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

/**
 * Definition of a service-specific experiment-config variable, as returned by `GET /ecs/defined-variables`.
 *
 * @property type a JSON schema object (e.g. `mapOf("\$schema" to "http://json-schema.org/draft-07/schema#",
 *   "type" to "integer", "minimum" to 0)`)
 * @property defaultValue the default value
 */
data class VariableDefinition(
    val type: Any,
    val defaultValue: Any,
)

/**
 * A variable the experiment-config-sidecar (ECS) can set at runtime. The ECS asks the app for its variables
 * (`GET /ecs/defined-variables`) and forwards values it does not handle itself (`POST /ecs/variables`).
 * Declare one bean per variable; without any bean the two endpoints do not exist (404). Example (shipment): `providerRetries`, `providerRetryDelay`.
 */
interface EcsVariable {
    val name: String
    val definition: VariableDefinition

    /** Applies a value received from the sidecar (JSON-decoded: Int/Long/Double/String/Boolean/Map/List). */
    fun set(value: Any)
}

/**
 * Serves the experiment-config-sidecar endpoints for the declared [EcsVariable]s.
 */
@RestController
class ExperimentConfigController(private val variables: List<EcsVariable>) {

    @GetMapping("/ecs/defined-variables")
    fun getDefinedVariables(): Map<String, VariableDefinition> = variables.associate { it.name to it.definition }

    @PostMapping("/ecs/variables")
    fun setVariables(@RequestBody values: Map<String, Any>) {
        values.forEach { (name, value) ->
            val variable = variables.find { it.name == name } ?: error("Unknown variable: $name")
            variable.set(value)
        }
    }
}

/**
 * Experiment-config-sidecar endpoints, only if the service declares [EcsVariable] beans (registered in
 * `META-INF/spring/...AutoConfiguration.imports`, independent of [org.misarch.common.MisarchCommonAutoConfiguration]).
 */
@AutoConfiguration
@ConditionalOnBean(EcsVariable::class)
class EcsAutoConfiguration {
    @Bean
    fun experimentConfigController(variables: List<EcsVariable>) = ExperimentConfigController(variables)
}
