package org.misarch.order.service

import org.misarch.common.graphql.AuthorizedUser
import org.misarch.common.persistence.Condition
import org.misarch.common.persistence.ConditionQueries
import org.misarch.common.persistence.findByIdOrThrow
import org.misarch.order.client.CheckoutClient
import org.misarch.order.client.model.CalculateShipmentFeesInput
import org.misarch.order.client.model.DiscountDTO
import org.misarch.order.client.model.FindApplicableDiscountsInput
import org.misarch.order.client.model.FindApplicableDiscountsProductVariantInput
import org.misarch.order.client.model.ProductVariantVersionWithQuantityAndShipmentMethodInput
import org.misarch.order.graphql.input.CreateOrderInput
import org.misarch.order.graphql.input.OrderItemInput
import org.misarch.order.graphql.input.PlaceOrderInput
import org.misarch.order.persistence.model.CouponEntity
import org.misarch.order.persistence.model.OrderEntity
import org.misarch.order.persistence.model.ProductVariantEntity
import org.misarch.order.persistence.model.ShipmentMethodEntity
import org.misarch.order.persistence.model.TaxRateEntity
import org.misarch.order.persistence.model.UserAddressEntity
import org.misarch.order.persistence.repository.OrderRepository
import org.misarch.order.persistence.repository.UserRepository
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Creation and placement of orders: validation against the replicas, the calls to shoppingcart, inventory, discount
 * and shipment, pricing. The writes are [OrderService]'s transactions; the service calls are not part of a
 * transaction (a database connection would otherwise be held while they run).
 */
@Service
class CheckoutService(
    private val orderService: OrderService,
    private val orderRepository: OrderRepository,
    private val userRepository: UserRepository,
    private val conditionQueries: ConditionQueries,
    private val checkoutClient: CheckoutClient
) {

    /**
     * Creates a pending order from items of the user's shopping cart. Product variants that are unknown or not
     * publicly visible are not ordered.
     *
     * @param input the order to create
     * @param authorizedUser the user creating the order (forwarded to the shoppingcart service)
     * @return the created order
     */
    suspend fun createOrder(input: CreateOrderInput, authorizedUser: AuthorizedUser): OrderEntity {
        validateOrderInput(input)
        val orderedProductVariants = orderedProductVariants(input, authorizedUser)
        val productVariantIds = orderedProductVariants.keys.toList()
        val productVariants = conditionQueries.findAll<ProductVariantEntity>(Condition.inArray("t.id", productVariantIds))
            .filter { it.isPubliclyVisible }
            .associateBy { it.id }
            .let { found -> productVariantIds.mapNotNull { found[it] } }
        checkAvailability(orderedProductVariants)
        val taxRates = conditionQueries.findAll<TaxRateEntity>(
            Condition.inArray("t.id", productVariants.map { it.currentVersionTaxRateId }.distinct())
        ).associateBy { it.id }
        val discounts = applicableDiscounts(input.userId, orderedProductVariants, productVariants)
        val shipmentFees = checkoutClient.shipmentFees(CalculateShipmentFeesInput(productVariants.map {
            val ordered = orderedProductVariants.getValue(it.id)
            ProductVariantVersionWithQuantityAndShipmentMethodInput(it.currentVersionId, ordered.count, ordered.input.shipmentMethodId)
        }))
        val items = productVariants.map { productVariant ->
            val ordered = orderedProductVariants.getValue(productVariant.id)
            val taxRate = checkNotNull(taxRates[productVariant.currentVersionTaxRateId]) {
                "Tax rate with id ${productVariant.currentVersionTaxRateId} of product variant ${productVariant.id} does not exist"
            }
            val itemDiscounts = discounts.getValue(productVariant.id)
            PricedOrderItem(
                ordered.input, productVariant, taxRate, ordered.count, itemDiscounts,
                compensatableAmount(productVariant.currentVersionPrice, ordered.count, itemDiscounts)
            )
        }
        return orderService.createOrder(input, items, shipmentFees)
    }

    /**
     * Places a pending order (see [OrderService.placeOrder]); an order that is pending for more than
     * [PENDING_TIMEOUT] is rejected instead.
     *
     * @param input the order to place and the optional payment authorization
     * @param authorizedUser the authorized user (the owner of the order or an employee)
     * @return the placed order
     */
    suspend fun placeOrder(input: PlaceOrderInput, authorizedUser: AuthorizedUser): OrderEntity {
        val order = orderRepository.findByIdOrThrow(input.id)
        if (authorizedUser.id != order.userId) {
            authorizedUser.checkIsEmployee()
        }
        if (order.createdAt.plus(PENDING_TIMEOUT).isBefore(OffsetDateTime.now())) {
            orderService.rejectOrder(order)
            throw IllegalStateException("Order ${order.id} was rejected as it was pending for more than one hour")
        }
        return orderService.placeOrder(order, input.paymentAuthorization?.cvc)
    }

    /**
     * Checks that the user, the shipment methods, the coupons and the addresses exist; the shipment and the invoice
     * address must be (not archived) addresses of the user.
     */
    private suspend fun validateOrderInput(input: CreateOrderInput) {
        require(userRepository.existsById(input.userId)) { "User with id ${input.userId} does not exist" }
        val shipmentMethodIds = input.orderItemInputs.map { it.shipmentMethodId }.toSet()
        val knownShipmentMethodIds = findExisting<ShipmentMethodEntity>(shipmentMethodIds) { it.id }
        val unknownShipmentMethodId = shipmentMethodIds.firstOrNull { it !in knownShipmentMethodIds }
        require(unknownShipmentMethodId == null) { "Shipment method with id $unknownShipmentMethodId does not exist" }
        val couponIds = input.orderItemInputs.flatMap { it.couponIds }.toSet()
        val knownCouponIds = findExisting<CouponEntity>(couponIds) { it.id }
        val unknownCouponId = couponIds.firstOrNull { it !in knownCouponIds }
        require(unknownCouponId == null) { "Coupon with id $unknownCouponId does not exist" }
        val addressIds = setOf(input.shipmentAddressId, input.invoiceAddressId)
        val userAddressIds = conditionQueries.findAll<UserAddressEntity>(
            Condition.eq("t.userId", input.userId) and Condition.inArray("t.id", addressIds)
        ).map { it.key.id }
        val unknownAddressId = addressIds.firstOrNull { it !in userAddressIds }
        require(unknownAddressId == null) {
            "Address with id $unknownAddressId is not an address of user ${input.userId}"
        }
    }

    /** The ids of the entities with [ids] (no statement if [ids] is empty). */
    private suspend inline fun <reified E : Any> findExisting(ids: Set<UUID>, crossinline id: (E) -> UUID): Set<UUID> =
        if (ids.isEmpty()) emptySet() else conditionQueries.findAll<E>(Condition.inArray("t.id", ids)).map { id(it) }.toSet()

    /**
     * The ordered product variants with their count from the shopping cart, in the order of the inputs; for a product
     * variant in several inputs the last input counts.
     */
    private suspend fun orderedProductVariants(
        input: CreateOrderInput,
        authorizedUser: AuthorizedUser
    ): Map<UUID, OrderedProductVariant> {
        val cartItems = checkoutClient.shoppingCartItems(input.userId, authorizedUser).associateBy { it.id }
        val orderedProductVariants = LinkedHashMap<UUID, OrderedProductVariant>()
        for (itemInput in input.orderItemInputs) {
            val cartItem = requireNotNull(cartItems[itemInput.shoppingCartItemId]) {
                "Shopping cart item with id ${itemInput.shoppingCartItemId} is not in the shopping cart of user ${input.userId}"
            }
            orderedProductVariants[cartItem.productVariant.id] = OrderedProductVariant(itemInput, cartItem.count)
        }
        return orderedProductVariants
    }

    /** Checks that the inventory holds the ordered count of every ordered product variant. */
    private suspend fun checkAvailability(orderedProductVariants: Map<UUID, OrderedProductVariant>) {
        val inventoryCounts = checkoutClient.inventoryCounts(orderedProductVariants.keys.toList())
        val available = orderedProductVariants.all { (id, ordered) -> (inventoryCounts[id] ?: 0) >= ordered.count }
        require(available) { "Not all requested product variants are available" }
    }

    /**
     * The discounts applicable to each ordered product variant (by product variant id); the order amount is that of
     * the product variants that are ordered.
     */
    private suspend fun applicableDiscounts(
        userId: UUID,
        orderedProductVariants: Map<UUID, OrderedProductVariant>,
        productVariants: List<ProductVariantEntity>
    ): Map<UUID, List<DiscountDTO>> {
        val discounts = checkoutClient.applicableDiscounts(
            FindApplicableDiscountsInput(
                userId = userId,
                productVariants = orderedProductVariants.map { (id, ordered) ->
                    FindApplicableDiscountsProductVariantInput(id, ordered.count, ordered.input.couponIds.toSet())
                },
                orderAmount = orderAmount(productVariants.map {
                    it.currentVersionPrice to orderedProductVariants.getValue(it.id).count
                })
            )
        ).associate { it.productVariantId to it.discounts.distinctBy { discount -> discount.id } }
        return orderedProductVariants.keys.associateWith {
            checkNotNull(discounts[it]) { "The discount service returned no discounts for product variant $it" }
        }
    }

    /** An ordered product variant: the input that orders it and the count in the shopping cart. */
    private class OrderedProductVariant(val input: OrderItemInput, val count: Long)

    companion object {
        /** How long an order may stay pending before it can only be rejected. */
        val PENDING_TIMEOUT: Duration = Duration.ofHours(1)
    }

}
