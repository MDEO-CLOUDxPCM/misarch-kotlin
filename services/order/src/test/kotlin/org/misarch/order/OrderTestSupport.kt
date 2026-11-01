package org.misarch.order

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.misarch.common.testing.MisarchIntegrationTest
import org.misarch.order.graphql.model.OrderStatus
import org.misarch.order.persistence.model.OrderEntity
import org.misarch.order.persistence.model.OrderItemEntity
import org.misarch.order.persistence.model.OrderItemToDiscountEntity
import org.misarch.order.persistence.model.OrderItemToDiscountKey
import org.misarch.order.persistence.repository.OrderItemRepository
import org.misarch.order.persistence.repository.OrderItemToDiscountRepository
import org.misarch.order.persistence.repository.OrderRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.r2dbc.core.DatabaseClient
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Helpers shared by the order integration tests (replica events, orders inserted directly, raw SQL).
 */
abstract class OrderTestSupport : MisarchIntegrationTest() {

    override val graphqlPath: String = "/"

    @Autowired
    lateinit var databaseClient: DatabaseClient

    @Autowired
    lateinit var orderRepository: OrderRepository

    @Autowired
    lateinit var orderItemRepository: OrderItemRepository

    @Autowired
    lateinit var orderItemToDiscountRepository: OrderItemToDiscountRepository

    fun deliverOk(topic: String, data: String) {
        assertThat(deliverEvent("/subscription/$topic", topic, data)).isEqualTo(200)
    }

    fun registerUser(id: UUID = UUID.randomUUID()): UUID {
        deliverOk("user/user/created", """{"id":"$id","username":"x"}""")
        return id
    }

    fun registerUserAddress(userId: UUID, id: UUID = UUID.randomUUID()): UUID {
        deliverOk("address/user-address/created", """{"id":"$id","street1":"s","city":"c","userId":"$userId"}""")
        return id
    }

    fun archiveUserAddress(userId: UUID, id: UUID) {
        deliverOk("address/user-address/archived", """{"id":"$id","userId":"$userId"}""")
    }

    fun registerShipmentMethod(id: UUID = UUID.randomUUID()): UUID {
        deliverOk("shipment/shipment-method/created", """{"id":"$id","name":"x"}""")
        return id
    }

    fun registerCoupon(id: UUID = UUID.randomUUID()): UUID {
        deliverOk("discount/coupon/created", """{"id":"$id","code":"X"}""")
        return id
    }

    /** A tax rate with one version; returns (taxRateId, versionId). */
    fun registerTaxRate(rate: Double = 0.19): Pair<UUID, UUID> {
        val taxRateId = UUID.randomUUID()
        val versionId = UUID.randomUUID()
        deliverOk(
            "tax/tax-rate-version/created",
            """{"id":"$versionId","rate":$rate,"version":1,"createdAt":"2026-01-01T00:00:00Z","taxRateId":"$taxRateId"}"""
        )
        return taxRateId to versionId
    }

    /** A new current version of a product variant (catalog's payload); returns the version id. */
    fun registerProductVariantVersion(productVariantId: UUID, price: Long, taxRateId: UUID, id: UUID = UUID.randomUUID()): UUID {
        deliverOk(
            "catalog/product-variant-version/created",
            """{"id":"$id","name":"v","description":"d","version":1,"retailPrice":$price,"createdAt":"2026-01-01T00:00:00Z",
               "canBeReturnedForDays":30,"productVariantId":"$productVariantId","taxRateId":"$taxRateId","weight":0.5,"mediaIds":[]}"""
        )
        return id
    }

    /** Inserts an order with [items] items (compensatable amounts 10, 11, ...), each with one discount. */
    fun insertOrder(
        userId: UUID,
        createdAt: OffsetDateTime = now(),
        status: OrderStatus = OrderStatus.PENDING,
        items: Int = 1,
    ): StoredOrder = runBlocking {
        val order = orderRepository.save(
            OrderEntity(
                userId = userId, createdAt = createdAt, orderStatus = status,
                placedAt = if (status == OrderStatus.PLACED) createdAt else null, rejectionReason = null,
                shipmentAddressId = UUID.randomUUID(), invoiceAddressId = UUID.randomUUID(),
                compensatableOrderAmount = (0 until items).sumOf { 10L + it }, paymentInformationId = UUID.randomUUID(),
                vatNumber = null, id = null,
            )
        )
        val orderItems = orderItemRepository.saveAll((0 until items).map { position ->
            orderItem(order.id!!, position, createdAt, compensatableAmount = 10L + position)
        }).toList()
        orderItemToDiscountRepository.saveAll(orderItems.map {
            OrderItemToDiscountEntity(OrderItemToDiscountKey(it.id!!, UUID.randomUUID()), 1.0)
        }).toList()
        StoredOrder(order, orderItems)
    }

    fun orderItem(orderId: UUID, position: Int, createdAt: OffsetDateTime = now(), compensatableAmount: Long = 20, id: UUID? = null) =
        OrderItemEntity(
            orderId = orderId, position = position, createdAt = createdAt, productVariantId = UUID.randomUUID(),
            productVariantIsPubliclyVisible = true, productVariantVersionId = UUID.randomUUID(),
            productVariantVersionPrice = 20, productVariantVersionTaxRateId = UUID.randomUUID(),
            taxRateVersionId = UUID.randomUUID(), taxRateVersionRate = 0.19, taxRateVersionVersion = 1,
            shoppingCartItemId = UUID.randomUUID(), count = 1, compensatableAmount = compensatableAmount,
            shipmentMethodId = UUID.randomUUID(), id = id,
        )

    /** An order inserted by [insertOrder] with its items (in their order). */
    data class StoredOrder(val order: OrderEntity, val items: List<OrderItemEntity>) {
        val id: UUID get() = order.id!!
    }

    fun findOrder(id: UUID): OrderEntity? = runBlocking { orderRepository.findById(id) }

    fun findItems(orderId: UUID): List<OrderItemEntity> = runBlocking { orderItemRepository.findByOrderIdOrderByPosition(orderId) }

    fun sql(statement: String): List<Map<String, Any?>> =
        databaseClient.sql(statement).fetch().all().collectList().block()!!



    fun employee() = authorizedUserHeader(UUID.randomUUID(), "employee")

    companion object {
        fun now(): OffsetDateTime = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS)

        /** Postgres orders UUIDs by their bytes, i.e. by their (lower case) string form. */
        val UUID_ORDER: Comparator<UUID> = compareBy { it.toString() }
    }
}
