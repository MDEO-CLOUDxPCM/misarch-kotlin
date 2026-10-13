package org.misarch.simulation.web

import tools.jackson.databind.JsonNode

/**
 * The class-validator constraints of the original DTOs (global `ValidationPipe()`, no whitelist, no transform):
 * messages in property declaration order, extra properties allowed and kept.
 */
object Validation {

    enum class Rule(val message: String) {
        /** `@IsUUID()` (validator 13.11, version "all", strings only). */
        UUID("must be a UUID"),

        /** `@IsNumber()` (JS numbers only; NaN and ±Infinity rejected). */
        NUMBER("must be a number conforming to the specified constraints"),

        /** `@IsString()`. */
        STRING("must be a string"),
    }

    private val UUID_PATTERN = Regex("^[0-9A-F]{8}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{12}$", RegexOption.IGNORE_CASE)

    val CREATE_PAYMENT = listOf("paymentId" to Rule.UUID, "amount" to Rule.NUMBER, "paymentType" to Rule.STRING)
    val UPDATE_PAYMENT = listOf("paymentId" to Rule.UUID, "status" to Rule.STRING)
    val CREATE_SHIPMENT = listOf("shipmentId" to Rule.UUID)
    val UPDATE_SHIPMENT = listOf("shipmentId" to Rule.UUID, "status" to Rule.STRING)

    /** Returns the constraint messages (empty = valid). Non-object bodies (arrays) have no properties. */
    fun validate(body: JsonNode, rules: List<Pair<String, Rule>>): List<String> = rules.mapNotNull { (property, rule) ->
        val value = if (body.isObject) body.get(property) else null
        if (matches(value, rule)) null else "$property ${rule.message}"
    }

    private fun matches(value: JsonNode?, rule: Rule): Boolean = when (rule) {
        Rule.UUID -> value != null && value.isString && UUID_PATTERN.matches(value.stringValue())
        Rule.NUMBER -> value != null && value.isNumber && value.doubleValue().isFinite()
        Rule.STRING -> value != null && value.isString
    }
}
