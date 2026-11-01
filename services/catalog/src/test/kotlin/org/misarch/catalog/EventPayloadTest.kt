package org.misarch.catalog

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.misarch.catalog.event.model.CategoryDTO
import org.misarch.catalog.event.model.CreatedProductVariantDTO
import org.misarch.catalog.event.model.ProductDTO
import org.misarch.catalog.event.model.ProductVariantVersionDTO
import org.misarch.catalog.event.model.UpdatedProductVariantDTO
import org.misarch.common.event.EventJson
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Event payload JSON matches the event contract that consumers expect: property order as declared, null properties
 * omitted (`NON_NULL`).
 */
class EventPayloadTest {

    private val u = UUID.fromString("00000000-0000-0000-0000-000000000001")

    @Test
    fun `product payload`() {
        assertEquals(
            """{"id":"$u","internalName":"intern","defaultVariantId":"$u","categoryIds":["$u"],"publiclyVisible":true}""",
            EventJson.toJson(ProductDTO(u, "intern", true, u, listOf(u)))
        )
    }

    @Test
    fun `product variant payloads`() {
        assertEquals(
            """{"id":"$u","productId":"$u","currentVersionId":"$u","publiclyVisible":false}""",
            EventJson.toJson(CreatedProductVariantDTO(u, u, u, false))
        )
        assertEquals("""{"id":"$u","publiclyVisible":true}""", EventJson.toJson(UpdatedProductVariantDTO(u, true)))
    }

    @Test
    fun `category payload`() {
        assertEquals("""{"id":"$u","name":"n","description":"d"}""", EventJson.toJson(CategoryDTO(u, "n", "d")))
    }

    @Test
    fun `product variant version payload omits a null canBeReturnedForDays`() {
        assertEquals(
            """{"id":"$u","name":"n","description":"d","version":1,"retailPrice":20,"createdAt":"2026-10-13T11:57:34.123456Z","productVariantId":"$u","taxRateId":"$u","weight":0.5,"mediaIds":[]}""",
            EventJson.toJson(ProductVariantVersionDTO(u, "n", "d", 1, 20, OffsetDateTime.parse("2026-10-13T11:57:34.123456Z"), null, u, u, 0.5, listOf()))
        )
        assertEquals(
            """{"id":"$u","name":"n","description":"d","version":1,"retailPrice":20,"createdAt":"2026-10-13T11:57:34.1Z","canBeReturnedForDays":30,"productVariantId":"$u","taxRateId":"$u","weight":1.0,"mediaIds":["$u"]}""",
            EventJson.toJson(ProductVariantVersionDTO(u, "n", "d", 1, 20, OffsetDateTime.parse("2026-10-13T11:57:34.100Z"), 30, u, u, 1.0, listOf(u)))
        )
    }
}
