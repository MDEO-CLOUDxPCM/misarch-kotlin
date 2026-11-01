package org.misarch.order.event.model

import com.fasterxml.jackson.annotation.JsonAlias
import com.fasterxml.jackson.annotation.JsonProperty
import java.util.UUID

/**
 * DTO for an updated product variant
 *
 * @property id unique identifier of the product variant
 * @property isPubliclyVisible whether the product variant is publicly visible (catalog publishes `publiclyVisible`)
 */
data class ProductVariantDTO(
    val id: UUID,
    @JsonProperty("publiclyVisible")
    @JsonAlias("isPubliclyVisible")
    val isPubliclyVisible: Boolean
)
