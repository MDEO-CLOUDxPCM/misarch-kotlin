package org.misarch.common

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.misarch.common.event.EventJson
import org.misarch.common.event.MillisecondTimestampSerializer
import tools.jackson.databind.annotation.JsonSerialize
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

class EventJsonTest {

    class Name(val firstName: String, val lastName: String)
    abstract class Base(val id: UUID, val name: Name?, val companyName: String?)
    class Sub(id: UUID, name: Name?, companyName: String?, val isPubliclyVisible: Boolean, val userId: UUID) :
        Base(id, name, companyName)

    @JsonInclude(JsonInclude.Include.ALWAYS)
    class WithNulls(val a: String?, @get:JsonProperty("isActive") val isActive: Boolean, val at: OffsetDateTime)

    @Test
    fun `serializes like the Dapr SDK 1_11 default serializer`() {
        val id = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val json = EventJson.toJson(Sub(id, null, null, true, id))
        assertEquals(
            """{"id":"$id","publiclyVisible":true,"userId":"$id"}""".let { sortedKeys(it) },
            sortedKeys(json)
        )
    }

    @Test
    fun `can keep nulls, is-prefix and writes ISO dates`() {
        val json = EventJson.toJson(WithNulls(null, true, OffsetDateTime.of(2026, 1, 2, 3, 4, 5, 0, ZoneOffset.UTC)))
        assertEquals(sortedKeys("""{"a":null,"isActive":true,"at":"2026-01-02T03:04:05Z"}"""), sortedKeys(json))
    }

    class WithMillis(@get:JsonSerialize(using = MillisecondTimestampSerializer::class) val at: OffsetDateTime)

    @Test
    fun `millisecond fields keep three digits in UTC`() {
        val at = OffsetDateTime.of(2026, 10, 13, 14, 57, 21, 730_000_000, ZoneOffset.ofHours(2))
        assertEquals("""{"at":"2026-10-13T12:57:21.730Z"}""", EventJson.toJson(WithMillis(at)))
        assertEquals("""{"at":"2026-10-13T12:57:21.000Z"}""", EventJson.toJson(WithMillis(at.withNano(0))))
    }

    private fun sortedKeys(json: String): Map<String, Any?> =
        tools.jackson.databind.json.JsonMapper.builder().build().readValue(json, Map::class.java)
            .entries.associate { it.key.toString() to it.value }.toSortedMap()
}
