package org.misarch.simulation.config

import org.misarch.simulation.js.Js
import org.misarch.simulation.js.JsJson
import org.slf4j.LoggerFactory
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode
import tools.jackson.databind.node.StringNode
import java.util.concurrent.ConcurrentHashMap

/** Thrown for a variable name that is not defined (the original throws `Error`, i.e. HTTP 500). */
class UndefinedVariableException(name: String) : RuntimeException("Variable $name is not defined")

/**
 * Port of the original `ConfigurationService`: the six experiment-config variables (rate limit, processing time and
 * success rate per provider), exposed to the experiment-config sidecar, plus env lookups for everything else.
 *
 * Details (docs/specs/simulation.md §2):
 * - the defaults are written into the variable map at start-up; an env var with the name of a defined variable
 *   replaces the default (fix S4; the original ignored such env vars, quirk Q1);
 * - `POST /ecs/variables` applies entries in body order with JavaScript coercion (`integer` → `parseInt(String(v))`,
 *   `number` → `Number(v)`); an unknown name throws after the earlier entries were applied.
 */
@Component
class SimulationVariables(private val environment: Environment?) {

    private val log = LoggerFactory.getLogger(SimulationVariables::class.java)

    private val values = ConcurrentHashMap<String, Double>()

    init {
        DEFINITIONS.properties().forEach { (name, definition) -> set(name, definition["defaultValue"]) }
        // fix S4 (docs/fixes/simulation.md): an environment value of a defined variable replaces its default (the
        // original wrote the defaults into the variable map, which lookups prefer, so env values never took effect)
        DEFINITIONS.properties().forEach { (name, _) ->
            val envValue = env(name)?.takeIf { it.isNotBlank() } ?: return@forEach
            val previous = values.getValue(name)
            set(name, StringNode(envValue))
            if (values.getValue(name).isNaN()) {
                log.warn("Ignoring the non-numeric environment value {}={}", name, envValue)
                values[name] = previous
            }
        }
    }

    /** Body of `GET /ecs/defined-variables` (same order and content as the original). */
    fun definedVariables(): JsonNode = DEFINITIONS.deepCopy()

    /** `setVariables(variables)` of the original (body of `POST /ecs/variables`). */
    fun setVariables(variables: JsonNode) {
        val entries: List<Pair<String, JsonNode>> = when {
            variables.isObject -> variables.properties().map { it.key to it.value }
            // Object.entries of an array: index keys
            variables.isArray -> variables.values().mapIndexed { i, v -> i.toString() to v }
            else -> emptyList()
        }
        entries.forEach { (name, value) -> set(name, value) }
    }

    private fun set(name: String, value: JsonNode?) {
        val definition = DEFINITIONS[name] ?: throw UndefinedVariableException(name)
        val coerced = when (val type = definition["type"]["type"].stringValue()) {
            "integer" -> Js.parseInt(JsJson.toJsString(value))
            "number" -> JsJson.toNumber(value)
            else -> throw IllegalStateException("Variable $name has an unsupported type $type")
        }
        log.info("Setting variable {} to {}", name, Js.numberToString(coerced))
        values[name] = coerced
    }

    /** Current value of a defined variable (always present: defaults are set at start-up). */
    fun number(name: String): Double = values[name] ?: throw UndefinedVariableException(name)

    /** A non-exposed variable: read from the environment (the original's Nest `ConfigService`, i.e. `process.env`). */
    fun env(name: String): String? = environment?.getProperty(name)

    companion object {
        const val PAYMENTS_PER_MINUTE = "PAYMENTS_PER_MINUTE"
        const val SHIPMENTS_PER_MINUTE = "SHIPMENTS_PER_MINUTE"
        const val PAYMENT_PROCESSING_TIME = "PAYMENT_PROCESSING_TIME"
        const val SHIPMENT_PROCESSING_TIME = "SHIPMENT_PROCESSING_TIME"
        const val PAYMENT_SUCCESS_RATE = "PAYMENT_SUCCESS_RATE"
        const val SHIPMENT_SUCCESS_RATE = "SHIPMENT_SUCCESS_RATE"

        /** `variable-definitions.ts` of the original, verbatim (key order matters for the JSON answer). */
        val DEFINITIONS: ObjectNode = JsJson.parse(
            """
            {
              "PAYMENTS_PER_MINUTE": {"type": {"${'$'}schema": "http://json-schema.org/draft-07/schema#", "type": "integer"}, "defaultValue": 1000000},
              "SHIPMENTS_PER_MINUTE": {"type": {"${'$'}schema": "http://json-schema.org/draft-07/schema#", "type": "integer"}, "defaultValue": 1000000},
              "PAYMENT_PROCESSING_TIME": {"type": {"${'$'}schema": "http://json-schema.org/draft-07/schema#", "type": "integer"}, "defaultValue": 5},
              "SHIPMENT_PROCESSING_TIME": {"type": {"${'$'}schema": "http://json-schema.org/draft-07/schema#", "type": "integer"}, "defaultValue": 5},
              "PAYMENT_SUCCESS_RATE": {"type": {"${'$'}schema": "http://json-schema.org/draft-07/schema#", "type": "number"}, "defaultValue": 0.95},
              "SHIPMENT_SUCCESS_RATE": {"type": {"${'$'}schema": "http://json-schema.org/draft-07/schema#", "type": "number"}, "defaultValue": 0.95}
            }
            """.trimIndent()
        ) as ObjectNode
    }
}
