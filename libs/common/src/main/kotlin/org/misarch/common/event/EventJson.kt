package org.misarch.common.event

import com.fasterxml.jackson.annotation.JsonInclude
import tools.jackson.databind.MapperFeature
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.SerializationFeature
import tools.jackson.databind.json.JsonMapper

/**
 * JSON serialization of published event payloads.
 *
 * Reproduces what the original Kotlin services published through the Dapr Java SDK 1.11 `DefaultObjectSerializer`
 * (a plain Jackson 2 `ObjectMapper`, *no* Kotlin module, `NON_NULL` inclusion):
 * - `null` properties are omitted (override per class/property with `@JsonInclude(JsonInclude.Include.ALWAYS)`
 *   for payloads that contain explicit `null`s),
 * - Kotlin `val isFoo: Boolean` is serialized as `"foo"` (bean naming; use `@get:JsonProperty("isFoo")` where
 *   the original wrote `isFoo`),
 * - properties in declaration order,
 * - `java.time` values as ISO-8601 strings (the original Kotlin services had no dates in events).
 * Only `com.fasterxml.jackson.annotation.*` annotations apply (shared by Jackson 2 and 3).
 */
object EventJson {

    val mapper: ObjectMapper = JsonMapper.builder()
        .disable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
        .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
        .changeDefaultPropertyInclusion { it.withValueInclusion(JsonInclude.Include.NON_NULL) }
        .build()

    fun toBytes(payload: Any): ByteArray = mapper.writeValueAsBytes(payload)

    fun toJson(payload: Any): String = mapper.writeValueAsString(payload)
}
