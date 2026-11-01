package org.misarch.common.event

import tools.jackson.core.JsonGenerator
import tools.jackson.databind.SerializationContext
import tools.jackson.databind.ValueSerializer
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Writes a payload timestamp of millisecond precision in UTC with exactly three fraction digits
 * (`2026-10-13T12:57:21.730Z`); reading is unaffected. Use on the getter:
 * `@get:JsonSerialize(using = MillisecondTimestampSerializer::class)`.
 */
class MillisecondTimestampSerializer : ValueSerializer<OffsetDateTime>() {

    override fun serialize(value: OffsetDateTime, gen: JsonGenerator, ctxt: SerializationContext) {
        gen.writeString(FORMAT.format(value.withOffsetSameInstant(ZoneOffset.UTC)))
    }

    private companion object {
        val FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX")
    }
}
