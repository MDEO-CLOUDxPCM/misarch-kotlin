package org.misarch.common.nest

import tools.jackson.databind.JsonNode

/**
 * A faithful re-implementation of what NestJS' global `ValidationPipe` (class-validator 0.14 + class-transformer,
 * `forbidUnknownValues: false`, no whitelist) does with a DTO class, including the message texts and NestJS'
 * flattening of nested errors (`orderItems.0.count must not be less than 1`). The test corpus generated from the
 * original DTO classes (the `reference` validation corpora in the test resources) pins the behaviour.
 *
 * A [Schema] lists the decorated properties in declaration order; the constraints of one property in
 * class-validator's registration order, i.e. **bottom-up** (the decorator closest to the property first).
 */
object ClassValidator {

    /** One validation decorator. [message] receives the property name. */
    class Constraint(
        val name: String,
        val each: Boolean = false,
        val message: (String) -> String,
        val test: (JsonNode?) -> Boolean,
    )

    /** `@ValidateNested({each})` + `@Type(() => schema)`. */
    class Nested(val schema: () -> Schema, val each: Boolean)

    /** [default]: the class field initializer (applies when the plain object lacks the property). */
    class Property(
        val name: String,
        val constraints: List<Constraint> = emptyList(),
        val optional: Boolean = false,
        val nested: Nested? = null,
        val default: JsonNode? = null,
    )

    class Schema(val properties: List<Property>)

    private class ValidationError(val property: String) {
        val constraints = LinkedHashMap<String, String>()
        val children = mutableListOf<ValidationError>()
    }

    /**
     * Validates [value] (the request body / argument object; null or missing → `{}`, like `ValidationPipe`) and
     * returns the flattened messages (empty = valid).
     */
    fun validate(schema: Schema, value: JsonNode?): List<String> {
        val target = if (value == null || value.isNull || value.isMissingNode) Js.nodes.objectNode() else value
        val errors = execute(schema, target)
        return strip(errors).flatMap { mapChildren(it, null) }.flatMap { it.second.values }
    }

    /** Throws the Nest `BadRequestException` if [value] is invalid. */
    fun validateOrThrow(schema: Schema, value: JsonNode?) {
        val messages = validate(schema, value)
        if (messages.isNotEmpty()) throw BadRequestException(messages)
    }

    private fun execute(schema: Schema, obj: JsonNode): MutableList<ValidationError> {
        val errors = mutableListOf<ValidationError>()
        for (property in schema.properties) {
            val raw = if (obj.isObject) obj.get(property.name) else null
            val value = raw?.takeUnless { it.isMissingNode } ?: property.default
            val error = ValidationError(property.name)
            errors += error
            if (property.optional && (value == null || value.isNull)) continue
            for (constraint in property.constraints) {
                val valid = if (constraint.each && value != null && value.isArray) {
                    value.all { constraint.test(it) }
                } else {
                    constraint.test(value)
                }
                if (!valid) {
                    error.constraints[constraint.name] =
                        (if (constraint.each) "each value in " else "") + constraint.message(property.name)
                }
            }
            property.nested?.let { nested(property.name, it, value, error) }
        }
        return errors
    }

    private fun nested(propertyName: String, nested: Nested, value: JsonNode?, error: ValidationError) {
        if (value == null) return
        when {
            value.isArray -> value.forEachIndexed { index, element ->
                val child = ValidationError(index.toString())
                error.children += child
                nested(propertyName, nested, element, child)
            }
            value.isObject -> error.children += execute(nested.schema(), value)
            else -> error.constraints["nestedValidation"] =
                (if (nested.each) "each value in " else "") + "nested property $propertyName must be either object or array"
        }
    }

    private fun strip(errors: List<ValidationError>): List<ValidationError> = errors.filter { error ->
        val kept = strip(error.children)
        error.children.clear()
        error.children += kept
        error.constraints.isNotEmpty() || error.children.isNotEmpty()
    }

    /** NestJS `mapChildrenToValidationErrors` + `prependConstraintsWithParentProp`. */
    private fun mapChildren(error: ValidationError, parentPath: String?): List<Pair<String, Map<String, String>>> {
        if (error.children.isEmpty()) return listOf(error.property to error.constraints)
        val path = if (parentPath != null) "$parentPath.${error.property}" else error.property
        val result = mutableListOf<Pair<String, Map<String, String>>>()
        for (item in error.children) {
            if (item.children.isNotEmpty()) result += mapChildren(item, path)
            result += item.property to item.constraints.mapValues { "$path.${it.value}" }
        }
        return result
    }

    // ---- constraint factories (class-validator 0.14 default messages) ----

    private val UUID_ALL = Regex("^[0-9A-F]{8}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{12}$", RegexOption.IGNORE_CASE)
    private val UUID_V4 = Regex("^[0-9A-F]{8}-[0-9A-F]{4}-4[0-9A-F]{3}-[89AB][0-9A-F]{3}-[0-9A-F]{12}$", RegexOption.IGNORE_CASE)

    private const val JS_WS = "\\s\\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF"

    /** validator.js 13.11 `isISO8601` (non-strict), `\s` widened to JavaScript's whitespace class. */
    private val ISO8601 = Regex(
        "^([\\+-]?\\d{4}(?!\\d{2}\\b))((-?)((0[1-9]|1[0-2])(\\3([12]\\d|0[1-9]|3[01]))?|W([0-4]\\d|5[0-3])(-?[1-7])?|" +
            "(00[1-9]|0[1-9]\\d|[12]\\d{2}|3([0-5]\\d|6[1-6])))([T$JS_WS]((([01]\\d|2[0-3])((:?)[0-5]\\d)?|24:?00)" +
            "([\\.,]\\d+(?!:))?)?(\\17[0-5]\\d([\\.,]\\d+)?)?([zZ]|([\\+-])([01]\\d|2[0-3]):?([0-5]\\d)?)?)?)?$"
    )

    fun isUUID(each: Boolean = false, version4: Boolean = false) = Constraint(
        "isUuid", each, { "$it must be a UUID" },
        { Js.isString(it) && (if (version4) UUID_V4 else UUID_ALL).matches(it!!.asString()) }
    )

    fun isDateString() = Constraint(
        "isDateString", false, { "$it must be a valid ISO 8601 date string" },
        { Js.isString(it) && ISO8601.matches(it!!.asString()) }
    )

    fun isEnum(values: List<String>) = Constraint(
        "isEnum", false, { "$it must be one of the following values: ${values.joinToString(", ")}" },
        { Js.isString(it) && it!!.asString() in values }
    )

    fun isInt() = Constraint("isInt", false, { "$it must be an integer number" }, { Js.isInteger(it) })

    fun min(min: Long) = Constraint(
        "min", false, { "$it must not be less than $min" },
        { Js.isNumber(it) && it!!.asDouble() >= min }
    )

    fun isArray() = Constraint("isArray", false, { "$it must be an array" }, { it != null && it.isArray })

    fun isNumber() = Constraint(
        "isNumber", false, { "$it must be a number conforming to the specified constraints" }, { Js.isNumber(it) }
    )

    fun isString() = Constraint("isString", false, { "$it must be a string" }, { Js.isString(it) })

    fun isObject() = Constraint("isObject", false, { "$it must be an object" }, { Js.isObject(it) })

    fun custom(name: String, message: String, test: (JsonNode?) -> Boolean) = Constraint(name, false, { message }, test)

    /** Matches an ISO 8601 string like class-validator's `IsDateString` (exposed for tests). */
    fun isIso8601(value: String): Boolean = ISO8601.matches(value)
}
