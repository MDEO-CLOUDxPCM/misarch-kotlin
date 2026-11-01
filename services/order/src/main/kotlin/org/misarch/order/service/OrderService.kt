package org.misarch.order.service

import kotlinx.coroutines.flow.toList
import org.misarch.common.event.EventPublisher
import org.misarch.common.graphql.AuthorizedUser
import org.misarch.common.persistence.Condition
import org.misarch.common.persistence.ConditionQueries
import org.misarch.common.persistence.findByIdOrThrow
import org.misarch.order.event.OrderEvents
import org.misarch.order.event.model.OrderItemDTO
import org.misarch.order.graphql.input.CreateOrderInput
import org.misarch.order.graphql.input.OrderItemInput
import org.misarch.order.graphql.input.PlaceOrderInput
import org.misarch.order.graphql.model.OrderStatus
import org.misarch.order.persistence.model.CouponEntity
import org.misarch.order.persistence.model.OrderEntity
import org.misarch.order.persistence.model.OrderItemEntity
import org.misarch.order.persistence.model.OrderItemToDiscountEntity
import org.misarch.order.persistence.model.OrderItemToDiscountId
import org.misarch.order.persistence.model.ProductVariantEntity
import org.misarch.order.persistence.model.ShipmentMethodEntity
import org.misarch.order.persistence.model.TaxRateEntity
import org.misarch.order.persistence.model.UserAddressEntity
import org.misarch.order.persistence.repository.OrderItemRepository
import org.misarch.order.persistence.repository.OrderItemToDiscountRepository
import org.misarch.order.persistence.repository.OrderRepository
import org.misarch.order.persistence.repository.UserRepository
import org.misarch.order.remote.CheckoutClient
import org.misarch.order.remote.model.CalculateShipmentFeesInput
import org.misarch.order.remote.model.DiscountDTO
import org.misarch.order.remote.model.FindApplicableDiscountsInput
import org.misarch.order.remote.model.FindApplicableDiscountsProductVariantInput
import org.misarch.order.remote.model.ProductVariantVersionWithQuantityAndShipmentMethodInput
import org.springframework.stereotype.Service
import org.springframework.transaction.ReactiveTransactionManager
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Service for [OrderEntity]s: creation (validation against the replicas, the calls to shoppingcart, inventory,
 * discount and shipment, pricing) and placement.
 *
 * Writes run in a transaction of their own ([transactionalOperator]), which does not span the service invocations of
 * `createOrder` (a database connection would otherwise be held while they run).
 *
 * @param repository the repository for [OrderEntity]s
 * @param orderItemRepository the repository for order items
 * @param orderItemToDiscountRepository the repository for the discounts of order items
 * @param userRepository the repository for users
 * @param conditionQueries queries the replicas by id lists
 * @param checkoutClient calls the services needed to create an order
 * @param eventPublisher publishes placed orders
 * @param transactionManager the transaction manager
 */
@Service
class OrderService(
    private val repository: OrderRepository,
    private val orderItemRepository: OrderItemRepository,
    private val orderItemToDiscountRepository: OrderItemToDiscountRepository,
    private val userRepository: UserRepository,
    private val conditionQueries: ConditionQueries,
    private val checkoutClient: CheckoutClient,
    private val eventPublisher: EventPublisher,
    transactionManager: ReactiveTransactionManager
) {

    private val transactionalOperator = TransactionalOperator.create(transactionManager)

    /**
     * Creates a pending order from items of the user's shopping cart. Product variants that are unknown or not
     * publicly visible are not ordered. The compensatable amount of the order is the sum of its items plus the
     * shipment fees.
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
        val createdAt = now()
        val items = productVariants.map { productVariant ->
            val ordered = orderedProductVariants.getValue(productVariant.id)
            val taxRate = taxRates[productVariant.currentVersionTaxRateId] ?: throw IllegalStateException(
                "Tax rate ${productVariant.currentVersionTaxRateId} of product variant ${productVariant.id} is unknown."
            )
            val itemDiscounts = discounts.getValue(productVariant.id)
            OrderItem(productVariant, taxRate, ordered, itemDiscounts,
                compensatableAmount(productVariant.currentVersionPrice, ordered.count, itemDiscounts))
        }
        val order = OrderEntity(
            userId = input.userId,
            createdAt = createdAt,
            orderStatus = OrderStatus.PENDING,
            placedAt = null,
            rejectionReason = null,
            shipmentAddressId = input.shipmentAddressId,
            invoiceAddressId = input.invoiceAddressId,
            compensatableOrderAmount = items.sumOf { it.compensatableAmount } + shipmentFees,
            paymentInformationId = input.paymentInformationId,
            vatNumber = input.vatNumber,
            id = null
        )
        return transactionalOperator.executeAndAwait { saveOrder(order, items) }
    }

    /**
     * Places a pending order and publishes it (`order/order/created`); the status change and the event are one
     * transaction. An order that is pending for more than [PENDING_TIMEOUT] is rejected instead.
     * The status changes are compare-and-set on PENDING: of concurrent placements only one succeeds.
     *
     * @param input the order to place and the optional payment authorization
     * @param authorizedUser the authorized user (the owner of the order or an employee)
     * @return the placed order
     */
    suspend fun placeOrder(input: PlaceOrderInput, authorizedUser: AuthorizedUser): OrderEntity {
        val order = repository.findByIdOrThrow(input.id)
        if (authorizedUser.id != order.userId) {
            authorizedUser.checkIsEmployee()
        }
        val orderId = order.id!!
        val now = now()
        if (order.createdAt.plus(PENDING_TIMEOUT).isBefore(now)) {
            check(repository.rejectIfPending(orderId) == 1) { notPendingMessage(orderId) }
            throw IllegalStateException("Order $orderId was rejected as it was pending for more than one hour.")
        }
        return transactionalOperator.executeAndAwait {
            check(repository.placeIfPending(orderId, now) == 1) { notPendingMessage(orderId) }
            order.orderStatus = OrderStatus.PLACED
            order.placedAt = now
            eventPublisher.publishEvent(
                OrderEvents.ORDER_CREATED, order.toEventDTO(orderItemDTOs(orderId), input.paymentAuthorization?.cvc)
            )
            order
        }
    }

    /**
     * Checks that the user, the shipment methods, the coupons and the addresses exist; the shipment and the invoice
     * address must be (not archived) addresses of the user.
     */
    private suspend fun validateOrderInput(input: CreateOrderInput) {
        require(userRepository.existsById(input.userId)) { "User ${input.userId} does not exist." }
        val shipmentMethodIds = input.orderItemInputs.map { it.shipmentMethodId }.toSet()
        val knownShipmentMethodIds = findExisting<ShipmentMethodEntity>(shipmentMethodIds) { it.id }
        shipmentMethodIds.firstOrNull { it !in knownShipmentMethodIds }?.let {
            throw IllegalArgumentException("Shipment method $it does not exist.")
        }
        val couponIds = input.orderItemInputs.flatMap { it.couponIds }.toSet()
        val knownCouponIds = findExisting<CouponEntity>(couponIds) { it.id }
        couponIds.firstOrNull { it !in knownCouponIds }?.let {
            throw IllegalArgumentException("Coupon $it does not exist.")
        }
        val addressIds = setOf(input.shipmentAddressId, input.invoiceAddressId)
        val userAddressIds = conditionQueries.findAll<UserAddressEntity>(
            Condition.eq("t.userId", input.userId) and Condition.inArray("t.id", addressIds)
        ).map { it.key.id }
        addressIds.firstOrNull { it !in userAddressIds }?.let {
            throw IllegalArgumentException("Address $it is not an address of user ${input.userId}.")
        }
    }

    /** The ids of the entities with [ids] (none if [ids] is empty). */
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
            val cartItem = cartItems[itemInput.shoppingCartItemId] ?: throw IllegalArgumentException(
                "Shopping cart item ${itemInput.shoppingCartItemId} is not in the shopping cart of user ${input.userId}."
            )
            orderedProductVariants[cartItem.productVariant.id] = OrderedProductVariant(itemInput, cartItem.count)
        }
        return orderedProductVariants
    }

    /** Checks that the inventory holds the ordered count of every ordered product variant. */
    private suspend fun checkAvailability(orderedProductVariants: Map<UUID, OrderedProductVariant>) {
        val inventoryCounts = checkoutClient.inventoryCounts(orderedProductVariants.keys.toList())
        val available = orderedProductVariants.all { (id, ordered) -> (inventoryCounts[id] ?: 0) >= ordered.count }
        require(available) { "Not all requested product variants are available." }
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
            discounts[it] ?: throw IllegalStateException("The discount service returned no discounts for product variant $it.")
        }
    }

    /** Saves the order, its items (in their order) and the discounts of the items. */
    private suspend fun saveOrder(order: OrderEntity, items: List<OrderItem>): OrderEntity {
        val savedOrder = repository.save(order)
        val savedItems = orderItemRepository.saveAll(items.mapIndexed { position, item ->
            item.toEntity(savedOrder.id!!, position, savedOrder.createdAt)
        }).toList()
        val itemDiscounts = savedItems.zip(items).flatMap { (savedItem, item) ->
            item.discounts.map { OrderItemToDiscountEntity(OrderItemToDiscountId(savedItem.id!!, it.id), it.discount) }
        }
        if (itemDiscounts.isNotEmpty()) {
            orderItemToDiscountRepository.saveAll(itemDiscounts).toList()
        }
        return savedOrder
    }

    /** The event DTOs of the items of an order, with the ids of their discounts. */
    private suspend fun orderItemDTOs(orderId: UUID): List<OrderItemDTO> {
        val items = orderItemRepository.findByOrderIdOrderByPosition(orderId)
        val discountIds = if (items.isEmpty()) {
            emptyMap()
        } else {
            conditionQueries.findAll<OrderItemToDiscountEntity>(
                Condition.inArray("t.orderItemId", items.map { it.id!! }), orderBy = listOf("t.discountId ASC")
            ).groupBy({ it.key.orderItemId }, { it.key.discountId })
        }
        return items.map { it.toEventDTO(discountIds[it.id] ?: emptyList()) }
    }

    private fun notPendingMessage(orderId: UUID) = "Order $orderId is not pending: it was already placed or rejected."

    private fun now(): OffsetDateTime = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS)

    /** An ordered product variant: the input that orders it and the count in the shopping cart. */
    private class OrderedProductVariant(val input: OrderItemInput, val count: Long)

    /** An order item before it is saved. */
    private class OrderItem(
        val productVariant: ProductVariantEntity,
        val taxRate: TaxRateEntity,
        val ordered: OrderedProductVariant,
        val discounts: List<DiscountDTO>,
        val compensatableAmount: Long
    ) {
        fun toEntity(orderId: UUID, position: Int, createdAt: OffsetDateTime) = OrderItemEntity(
            orderId = orderId,
            position = position,
            createdAt = createdAt,
            productVariantId = productVariant.id,
            productVariantIsPubliclyVisible = productVariant.isPubliclyVisible,
            productVariantVersionId = productVariant.currentVersionId,
            productVariantVersionPrice = productVariant.currentVersionPrice,
            productVariantVersionTaxRateId = productVariant.currentVersionTaxRateId,
            taxRateVersionId = taxRate.currentVersionId,
            taxRateVersionRate = taxRate.currentVersionRate,
            taxRateVersionVersion = taxRate.currentVersionVersion,
            shoppingCartItemId = ordered.input.shoppingCartItemId,
            count = ordered.count,
            compensatableAmount = compensatableAmount,
            shipmentMethodId = ordered.input.shipmentMethodId,
            id = null
        )
    }

    companion object {
        /** How long an order may stay pending before it can only be rejected. */
        val PENDING_TIMEOUT: Duration = Duration.ofHours(1)
    }

}
