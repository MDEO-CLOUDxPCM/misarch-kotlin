package org.misarch.order.persistence.model

import org.misarch.order.graphql.model.OrderStatus
import org.misarch.order.graphql.model.RejectionReason
import java.time.Instant
import java.util.UUID

/*
 * Rows of the tables that replace the MongoDB collections of the original (docs/specs/order.md §9).
 * u64/u32 values are held in Longs (bit pattern of the u64).
 */

/** `ProductVariantVersion` (embedded `current_version` of a product variant / snapshot in an order item). */
data class ProductVariantVersionRecord(val id: UUID, val price: Long, val taxRateId: UUID)

/** `TaxRateVersion` (embedded `current_version` of a tax rate / snapshot in an order item). */
data class TaxRateVersionRecord(val id: UUID, val rate: Double, val version: Long)

/** `Discount` of an order item (`internal_discounts`). */
data class DiscountRecord(val id: UUID, val discount: Double)

/**
 * Product variant replica (`product_variants`). [visibilityRaw] is set when a `catalog/product-variant/updated` event
 * carried a *string* (the original `$set` it, which made the document undeserializable).
 */
data class ProductVariantRecord(
    val id: UUID,
    val currentVersion: ProductVariantVersionRecord,
    val isPubliclyVisible: Boolean,
    val visibilityRaw: String?,
)

/** Tax rate replica (`tax_rates`). */
data class TaxRateRecord(val id: UUID, val currentVersion: TaxRateVersionRecord)

/** An order item (`orders.internal_order_items[]` → `order_items` + `order_item_discounts`). */
data class OrderItemRecord(
    val id: UUID,
    val createdAt: Instant,
    val productVariantId: UUID,
    val productVariantIsPubliclyVisible: Boolean,
    val productVariantVersion: ProductVariantVersionRecord,
    val taxRateVersion: TaxRateVersionRecord,
    val shoppingCartItemId: UUID,
    val count: Long,
    val compensatableAmount: Long,
    val shipmentMethodId: UUID,
    /** Rust `BTreeSet<Discount>`: unique ids, ascending byte order. */
    val discounts: List<DiscountRecord>,
)

/** An order (`orders`). */
data class OrderRecord(
    val id: UUID,
    val userId: UUID,
    val createdAt: Instant,
    val orderStatus: OrderStatus,
    val placedAt: Instant?,
    val rejectionReason: RejectionReason?,
    val items: List<OrderItemRecord>,
    val shipmentAddressId: UUID,
    val invoiceAddressId: UUID,
    val compensatableOrderAmount: Long,
    val paymentInformationId: UUID,
    val vatNumber: String?,
)

/** An order compensation (`order_compensations`). */
data class OrderCompensationRecord(
    val id: UUID,
    val orderId: UUID,
    val orderItemIds: List<UUID>,
    val triggeredAt: Instant,
    val amountToCompensate: Long,
)
