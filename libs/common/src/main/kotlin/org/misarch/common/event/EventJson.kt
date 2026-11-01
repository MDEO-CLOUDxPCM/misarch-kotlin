package org.misarch.common.event

import com.fasterxml.jackson.annotation.JsonInclude
import tools.jackson.databind.MapperFeature
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.SerializationFeature
import tools.jackson.databind.json.JsonMapper

/**
 * JSON serialization of published event payloads.
 *
 * The event contract of all services (`reference/events`), the format of a plain Jackson `ObjectMapper` without
 * Kotlin module and with `NON_NULL` inclusion:
 * - `null` properties are omitted (override per class/property with `@JsonInclude(JsonInclude.Include.ALWAYS)`
 *   for payloads that contain explicit `null`s),
 * - Kotlin `val isFoo: Boolean` is serialized as `"foo"` (bean naming; use `@get:JsonProperty("isFoo")` where
 *   the payload has `isFoo`),
 * - properties in declaration order,
 * - `java.time` values as ISO-8601 strings, fraction without trailing zeros (`2026-10-13T12:05:55.19028313Z`);
 *   a payload field with millisecond precision is truncated to milliseconds and written with exactly three digits
 *   ([MillisecondTimestampSerializer]: `2026-10-13T12:57:21.730Z`).
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
