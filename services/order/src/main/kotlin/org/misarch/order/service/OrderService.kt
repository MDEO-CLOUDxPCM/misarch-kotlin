package org.misarch.order.service

import org.misarch.common.graphql.AuthorizedUser
import org.misarch.order.event.OrderEventPublisher
import org.misarch.order.graphql.input.CreateOrderInput
import org.misarch.order.graphql.model.OrderStatus
import org.misarch.order.persistence.OrderStore
import org.misarch.order.persistence.model.DiscountRecord
import org.misarch.order.persistence.model.OrderItemRecord
import org.misarch.order.persistence.model.OrderRecord
import org.misarch.order.persistence.model.ProductVariantRecord
import org.misarch.order.persistence.model.ProductVariantVersionRecord
import org.misarch.order.persistence.model.TaxRateVersionRecord
import org.misarch.order.remote.DiscountRequestItem
import org.misarch.order.remote.RemoteServices
import org.misarch.order.remote.ShipmentFeeRequestItem
import org.misarch.order.rust.RustError
import org.misarch.order.rust.RustMessages
import org.misarch.common.rust.RustDateTime
import org.misarch.order.rust.RustTypeNames
import org.misarch.order.rust.U64
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * `createOrder` and `placeOrder` of the original (mutation.rs), step by step in the original's order
 * (docs/specs/order.md §5.6, §5.7). Authorization of `createOrder` happens in the resolver before.
 */
@Service
class OrderService(
    private val store: OrderStore,
    private val remote: RemoteServices,
    private val publisher: OrderEventPublisher,
) {

    /** Loads an order or fails with the original's `query_object` message. */
    suspend fun queryOrder(id: UUID): OrderRecord =
        store.findOrder(id) ?: throw RustError(RustMessages.notFound(RustTypeNames.ORDER, id))

    /** `create_order` after `authorize_user`. */
    suspend fun createOrder(input: CreateOrderInput, authorizedUser: AuthorizedUser): OrderRecord {
        val inputs = normalizeOrderItemInputs(input.orderItemInputs.map {
            NormalizedOrderItemInput(it.shoppingCartItemId, it.shipmentMethodId, LinkedHashSet(it.couponIds))
        })
        validateOrderInput(input.userId, inputs)
        val now = RustDateTime.now()
        val items = createInternalOrderItems(authorizedUser, input.userId, inputs, now)
        val order = OrderRecord(
            id = UUID.randomUUID(),
            userId = input.userId,
            createdAt = now,
            orderStatus = OrderStatus.PENDING,
            placedAt = null,
            rejectionReason = null,
            items = items,
            shipmentAddressId = input.shipmentAddressId,
            invoiceAddressId = input.invoiceAddressId,
            compensatableOrderAmount = items.fold(0L) { sum, item -> sum + item.compensatableAmount },
            paymentInformationId = input.paymentInformationId,
            vatNumber = input.vatNumber,
        )
        try {
            store.insertOrder(order)
        } catch (e: Exception) {
            throw RustError("Adding order failed in MongoDB.")
        }
        return queryOrder(order.id)
    }

    /** `validate_order_input`: user, shipment methods, coupons, addresses (only the user is checked). */
    private suspend fun validateOrderInput(userId: UUID, inputs: List<NormalizedOrderItemInput>) {
        if (!store.userExists(userId)) throw RustError(RustMessages.notFound(RustTypeNames.USER, userId))
        val shipmentMethodIds = inputs.map { it.shipmentMethodId }
        validateAllPresent(shipmentMethodIds, store.existingIds("shipment_methods", shipmentMethodIds), RustTypeNames.SHIPMENT_METHOD)
        val couponIds = inputs.flatMap { it.couponIds }
        validateAllPresent(couponIds, store.existingIds("coupons", couponIds), RustTypeNames.COUPON)
        // validate_user_address (shipment, then invoice address) only checks that the user exists (defect, see spec)
    }

    /** `create_internal_order_items` + `query_or_obtain_order_item_attributes` + `zip_to_internal_order_items`. */
    private suspend fun createInternalOrderItems(
        authorizedUser: AuthorizedUser,
        userId: UUID,
        inputs: List<NormalizedOrderItemInput>,
        now: Instant,
    ): List<OrderItemRecord> {
        // S20 shoppingcart
        val cartLines = remote.shoppingCartLines(authorizedUser, userId)
        val (counts, inputsByProductVariant) = mapInputsToProductVariants(inputs, cartLines)
        val productVariantIds = counts.keys.toList()
        // local replicas: missing variants are ignored, invisible ones dropped
        val productVariants = queryProductVariants(productVariantIds)
        val versions = productVariants.mapValues { it.value.currentVersion }
        // S21 inventory (all variant ids)
        checkAvailability(remote.inventoryCounts(productVariantIds), counts)
        // tax rates of the found variants
        val taxRates = store.findTaxRates(versions.values.map { it.taxRateId }).associateBy { it.id }
        val taxRateVersions = versions.mapValues { (productVariantId, version) ->
            val taxRate = taxRates[version.taxRateId]
                ?: throw RustError(RustMessages.hashMapError(RustTypeNames.TAX_RATE, productVariantId))
            taxRate.currentVersion
        }
        // S22 discount (all variant ids)
        val discounts = queryDiscounts(userId, productVariantIds, counts, inputsByProductVariant, versions)
        // S23 shipment (found variants; result discarded)
        remote.shipmentFees(versions.map { (productVariantId, version) ->
            ShipmentFeeRequestItem(version.id, U64.toI64(counts.getValue(productVariantId)), inputsByProductVariant.getValue(productVariantId).shipmentMethodId)
        })
        return productVariants.map { (productVariantId, productVariant) ->
            buildOrderItem(
                inputsByProductVariant.getValue(productVariantId),
                productVariant,
                versions.getValue(productVariantId),
                taxRateVersions.getValue(productVariantId),
                counts.getValue(productVariantId),
                discounts.getValue(productVariantId),
                now,
            )
        }
    }

    /** `query_product_variants_by_product_variant_ids`: found and publicly visible variants. */
    private suspend fun queryProductVariants(ids: List<UUID>): LinkedHashMap<UUID, ProductVariantRecord> {
        val found = store.findProductVariants(ids)
        found.firstOrNull { it.visibilityRaw != null }?.let {
            // the original's document holds a string where a boolean is expected and cannot be deserialized
            throw RustError("invalid type: string \"${it.visibilityRaw}\", expected a boolean")
        }
        val byId = found.associateBy { it.id }
        val result = LinkedHashMap<UUID, ProductVariantRecord>()
        ids.forEach { id -> byId[id]?.takeIf { it.isPubliclyVisible }?.let { result[id] = it } }
        return result
    }

    /** `query_discounts_by_product_variant_ids`. */
    private suspend fun queryDiscounts(
        userId: UUID,
        productVariantIds: List<UUID>,
        counts: Map<UUID, Long>,
        inputsByProductVariant: Map<UUID, NormalizedOrderItemInput>,
        versions: Map<UUID, ProductVariantVersionRecord>,
    ): Map<UUID, List<DiscountRecord>> {
        val items = productVariantIds.map { id ->
            DiscountRequestItem(id, U64.toI64(counts.getValue(id)), inputsByProductVariant.getValue(id).couponIds)
        }
        val response = remote.applicableDiscounts(userId, items, orderAmount(versions.values.map { it.price }))
        val byProductVariant = LinkedHashMap<UUID, List<DiscountRecord>>()
        response.forEach { (productVariantId, discounts) ->
            byProductVariant[productVariantId] = discounts.map { DiscountRecord(it.id, it.discount) }
        }
        return productVariantIds.associateWith { id ->
            val discounts = byProductVariant.remove(id) ?: throw RustError(
                "Product variant of UUID: `$id` is not contained in the result which `findApplicableDiscounts` provides."
            )
            discountSet(discounts)
        }
    }

    /** `OrderItem::new`. */
    private fun buildOrderItem(
        input: NormalizedOrderItemInput,
        productVariant: ProductVariantRecord,
        version: ProductVariantVersionRecord,
        taxRateVersion: TaxRateVersionRecord,
        count: Long,
        discounts: List<DiscountRecord>,
        now: Instant,
    ) = OrderItemRecord(
        id = UUID.randomUUID(),
        createdAt = now,
        productVariantId = productVariant.id,
        productVariantIsPubliclyVisible = productVariant.isPubliclyVisible,
        productVariantVersion = version,
        taxRateVersion = taxRateVersion,
        shoppingCartItemId = input.shoppingCartItemId,
        count = count,
        compensatableAmount = compensatableAmount(version.price, discounts),
        shipmentMethodId = input.shipmentMethodId,
        discounts = discounts,
    )

    /**
     * `place_order` after loading the order and `authorize_user`: sets PLACED (or REJECTED after 1 h), re-reads the
     * order, publishes `order/order/created` with the optional [cvc] and returns the re-read order.
     */
    suspend fun placeOrder(id: UUID, cvc: Int?): OrderRecord {
        setStatusPlaced(id)
        val order = queryOrder(id)
        publisher.publishOrderCreated(order, cvc)
        return order
    }

    /** `set_status_placed`. */
    private suspend fun setStatusPlaced(id: UUID) {
        val now = Instant.now()
        val order = queryOrder(id)
        if (!order.createdAt.plus(PENDING_TIMEOUT).isBefore(now)) {
            if (order.orderStatus != OrderStatus.PENDING) {
                throw RustError(
                    "`${order.orderStatus.debugName}` must be `OrderStatus::Pending` to be able to be placed. Order was already placed or rejected."
                )
            }
            try {
                store.setPlaced(id, now.truncatedTo(java.time.temporal.ChronoUnit.MILLIS))
            } catch (e: Exception) {
                throw RustError("Placing order of id: `$id` failed in MongoDB.")
            }
        } else {
            try {
                store.setRejected(id)
            } catch (e: Exception) {
                throw RustError(
                    "Order should be rejected as it is `OrderStatus::Pending` for too long. Rejecting order of id: `$id` failed in MongoDB."
                )
            }
            throw RustError("Order of id: `$id` was rejected as it is `OrderStatus::Pending` for too long.")
        }
    }

    companion object {
        /** `PENDING_TIMEOUT`. */
        val PENDING_TIMEOUT: Duration = Duration.ofSeconds(3600)
    }
}
