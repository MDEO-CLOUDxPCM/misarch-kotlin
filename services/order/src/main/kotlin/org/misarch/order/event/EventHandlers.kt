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

    /** `on_id_creation_event`: inserts; FIXED: a duplicate id is acknowledged without change (was 500 forever). */
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
     * version of a known variant (visibility unchanged), else insert the variant as publicly visible - one atomic
     * upsert (FIXED: no duplicate-key 500 for concurrent or redelivered events).
     */
    suspend fun onProductVariantVersionCreation(topic: String, data: EventData.ProductVariantVersion) {
        if (topic != OrderEvents.PRODUCT_VARIANT_VERSION_CREATED) throw unexpected(topic)
        store.upsertProductVariantVersion(data.productVariantId, ProductVariantVersionRecord(data.id, data.retailPrice, data.taxRateId))
    }

    /**
     * `on_product_variant_update_event`: sets the visibility of a known variant (no upsert). FIXED: the original
     * expected the *string* `isPubliclyVisible`, catalog sends the boolean `publiclyVisible`, so every event was
     * rejected (422, redelivered forever) and visibility was never replicated.
     */
    suspend fun onProductVariantUpdate(topic: String, data: EventData.ProductVariantUpdate) {
        if (topic != OrderEvents.PRODUCT_VARIANT_UPDATED) throw unexpected(topic)
        store.setVisibility(data.id, data.isPubliclyVisible)
    }

    /** `on_tax_rate_version_creation_event`: upsert of the tax rate's current version. */
    suspend fun onTaxRateVersionCreation(topic: String, data: EventData.TaxRateVersion) {
        if (topic != OrderEvents.TAX_RATE_VERSION_CREATED) throw unexpected(topic)
        store.upsertTaxRate(TaxRateRecord(data.taxRateId, TaxRateVersionRecord(data.id, data.rate, data.version)))
    }

    /**
     * `on_user_address_creation_event`: adds the address id to the user. FIXED: idempotent (no duplicate entry on
     * redelivery) and an upsert of the user (an address event processed before the user event is not lost); the
     * address ids are now used to validate the addresses of `createOrder`.
     */
    suspend fun onUserAddressCreation(topic: String, data: EventData.UserAddress) {
        if (topic != OrderEvents.USER_ADDRESS_CREATED) throw unexpected(topic)
        store.addUserAddress(data.userId, data.id)
    }

    /** `on_user_address_archived_event`: `$pull`. */
    suspend fun onUserAddressArchived(topic: String, data: EventData.UserAddress) {
        if (topic != OrderEvents.USER_ADDRESS_ARCHIVED) throw unexpected(topic)
        store.pullUserAddress(data.userId, data.id)
    }

    /**
     * `on_shipment_creation_failed_event` / `compensate_order`. FIXED (docs/fixes/order.md): the route is now
     * subscribed; a failed *return* shipment (no `orderId`) is acknowledged without action; the items to compensate are
     * the listed items of the order that are not compensated yet (the original's `verify_items_uncompensated` was
     * inverted: it failed when ANY compensation shared no item with the event); a redelivery or an event whose items
     * are all compensated already is acknowledged without a second compensation. Amount = Σ compensatable amounts of
     * those items; the compensation is stored, then `order/order-compensation/created` is published.
     */
    suspend fun onShipmentCreationFailed(topic: String, data: EventData.ShipmentFailed) {
        if (topic != OrderEvents.SHIPMENT_CREATION_FAILED) throw unexpected(topic)
        val orderId = data.orderId ?: return
        val order = store.findOrder(orderId) ?: throw EventHandlingException("Order $orderId not found")
        val alreadyCompensated = store.compensatedItemIds(orderId)
        val items = order.items.filter { it.id in data.orderItemIds && it.id !in alreadyCompensated }
        if (items.isEmpty()) return
        val amount = items.fold(0L) { sum, item -> sum + item.compensatableAmount }
        val compensation = OrderCompensationRecord(UUID.randomUUID(), orderId, items.map { it.id }, RustDateTime.now(), amount)
        store.insertCompensation(compensation)
        publisher.publishOrderCompensationCreated(compensation)
    }

    private fun unexpected(topic: String) = EventHandlingException("Unexpected topic $topic")
}
