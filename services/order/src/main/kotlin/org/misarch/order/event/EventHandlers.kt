package org.misarch.order.event

import org.misarch.order.persistence.OrderStore
import org.misarch.order.persistence.model.OrderCompensationRecord
import org.misarch.order.persistence.model.ProductVariantVersionRecord
import org.misarch.order.persistence.model.TaxRateRecord
import org.misarch.order.persistence.model.TaxRateVersionRecord
import org.misarch.common.rust.RustDateTime
import org.springframework.stereotype.Service
import java.util.UUID

/** An event the handler does not accept (unexpected topic, failed validation): answered with 500. */
class EventHandlingException(message: String) : RuntimeException(message)

/**
 * Behaviour of the original's event handlers (http_event_service.rs, order_compensation.rs). Every exception is
 * answered with 500 by [EventRoutes].
 */
@Service
class EventHandlers(
    private val store: OrderStore,
    private val publisher: OrderEventPublisher,
) {

    /** `on_id_creation_event`: plain inserts, a duplicate id fails (500). */
    suspend fun onIdCreation(topic: String, id: UUID) {
        val table = when (topic) {
            OrderEvents.COUPON_CREATED -> "coupons"
            OrderEvents.SHIPMENT_METHOD_CREATED -> "shipment_methods"
            OrderEvents.USER_CREATED -> "users"
            else -> throw unexpected(topic)
        }
        store.insertId(table, id)
    }

    /**
     * `on_product_variant_version_creation_event` / `create_or_update_product_variant_in_mongodb`: update the current
     * version of a known variant (visibility unchanged), else insert the variant as publicly visible. A "corrupt"
     * replica (string visibility) does not deserialize in the original, so the insert path is taken and fails.
     */
    suspend fun onProductVariantVersionCreation(topic: String, data: EventData.ProductVariantVersion) {
        if (topic != OrderEvents.PRODUCT_VARIANT_VERSION_CREATED) throw unexpected(topic)
        val version = ProductVariantVersionRecord(data.id, data.retailPrice, data.taxRateId)
        val existing = store.findProductVariant(data.productVariantId)
        if (existing != null && existing.visibilityRaw == null) {
            store.updateProductVariantVersion(existing.id, version)
        } else {
            store.insertProductVariant(data.productVariantId, version)
        }
    }

    /**
     * `on_product_variant_update_event`: the original `$set`s the *string* `isPubliclyVisible` (catalog sends a
     * boolean, which is rejected with 422 before this point).
     */
    suspend fun onProductVariantUpdate(topic: String, data: EventData.ProductVariantUpdate) {
        if (topic != OrderEvents.PRODUCT_VARIANT_UPDATED) throw unexpected(topic)
        store.setVisibilityRaw(data.id, data.isPubliclyVisible)
    }

    /** `on_tax_rate_version_creation_event`: upsert of the tax rate's current version. */
    suspend fun onTaxRateVersionCreation(topic: String, data: EventData.TaxRateVersion) {
        if (topic != OrderEvents.TAX_RATE_VERSION_CREATED) throw unexpected(topic)
        store.upsertTaxRate(TaxRateRecord(data.taxRateId, TaxRateVersionRecord(data.id, data.rate, data.version)))
    }

    /** `on_user_address_creation_event`: `$push` (no upsert, no dedup). */
    suspend fun onUserAddressCreation(topic: String, data: EventData.UserAddress) {
        if (topic != OrderEvents.USER_ADDRESS_CREATED) throw unexpected(topic)
        store.pushUserAddress(data.userId, data.id)
    }

    /** `on_user_address_archived_event`: `$pull`. */
    suspend fun onUserAddressArchived(topic: String, data: EventData.UserAddress) {
        if (topic != OrderEvents.USER_ADDRESS_ARCHIVED) throw unexpected(topic)
        store.pullUserAddress(data.userId, data.id)
    }

    /**
     * `on_shipment_creation_failed_event` / `compensate_order` (route not subscribed in the original):
     * the order must exist; `verify_items_uncompensated` fails if ANY compensation shares no item with the event
     * (inverted check of the original); amount = Σ compensatable amounts of the listed items of the order; the
     * compensation is stored, then `order/order-compensation/created` is published.
     */
    suspend fun onShipmentCreationFailed(topic: String, data: EventData.ShipmentFailed) {
        if (topic != OrderEvents.SHIPMENT_CREATION_FAILED) throw unexpected(topic)
        store.findOrder(data.orderId) ?: throw EventHandlingException("Order ${data.orderId} not found")
        if (store.existsCompensationDisjointFrom(data.orderItemIds)) {
            throw EventHandlingException("Order items of UUIDs: `${data.orderItemIds}` could not be verfied.")
        }
        val order = store.findOrder(data.orderId) ?: throw EventHandlingException("Order ${data.orderId} not found")
        val amount = order.items.filter { it.id in data.orderItemIds }.fold(0L) { sum, item -> sum + item.compensatableAmount }
        val compensation = OrderCompensationRecord(UUID.randomUUID(), data.orderId, data.orderItemIds, RustDateTime.now(), amount)
        store.insertCompensation(compensation)
        publisher.publishOrderCompensationCreated(compensation)
    }

    private fun unexpected(topic: String) = EventHandlingException("Unexpected topic $topic")
}
