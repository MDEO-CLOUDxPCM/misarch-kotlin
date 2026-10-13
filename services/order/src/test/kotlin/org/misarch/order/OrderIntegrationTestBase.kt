package org.misarch.order

import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.misarch.common.testing.MisarchIntegrationTest
import org.misarch.order.event.OrderEvents
import org.misarch.order.graphql.model.OrderStatus
import org.misarch.order.persistence.OrderStore
import org.misarch.order.persistence.model.DiscountRecord
import org.misarch.order.persistence.model.OrderItemRecord
import org.misarch.order.persistence.model.OrderRecord
import org.misarch.order.persistence.model.ProductVariantVersionRecord
import org.misarch.order.persistence.model.TaxRateVersionRecord
import org.misarch.common.rust.RustDateTime
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.awaitRowsUpdated
import java.time.Instant
import java.util.UUID

/**
 * Shared helpers of the order integration tests (one application context, Postgres container and fake Dapr sidecar
 * per JVM; every test uses fresh random ids).
 */
abstract class OrderIntegrationTestBase : MisarchIntegrationTest() {

    override val graphqlPath: String = "/"

    @Autowired
    lateinit var store: OrderStore

    @Autowired
    lateinit var databaseClient: DatabaseClient

    fun event(route: String, topic: String, data: String): Int = deliverEvent(route, topic, data)

    fun registerUser(id: UUID = UUID.randomUUID()): UUID {
        assertThat(event(OrderEvents.ROUTE_ID_CREATION, OrderEvents.USER_CREATED, """{"id":"$id"}""")).isEqualTo(200)
        return id
    }

    fun registerUserAddress(userId: UUID, addressId: UUID = UUID.randomUUID()): UUID {
        assertThat(
            event(OrderEvents.ROUTE_USER_ADDRESS_CREATION, OrderEvents.USER_ADDRESS_CREATED, """{"id":"$addressId","userId":"$userId"}""")
        ).isEqualTo(200)
        return addressId
    }

    fun registerShipmentMethod(id: UUID = UUID.randomUUID()): UUID {
        assertThat(event(OrderEvents.ROUTE_ID_CREATION, OrderEvents.SHIPMENT_METHOD_CREATED, """{"id":"$id"}""")).isEqualTo(200)
        return id
    }

    fun registerCoupon(id: UUID = UUID.randomUUID()): UUID {
        assertThat(event(OrderEvents.ROUTE_ID_CREATION, OrderEvents.COUPON_CREATED, """{"id":"$id","code":"X"}""")).isEqualTo(200)
        return id
    }

    /** A tax rate with one version; returns (taxRateId, versionId). */
    fun registerTaxRate(rate: Double = 0.19): Pair<UUID, UUID> {
        val taxRateId = UUID.randomUUID()
        val versionId = UUID.randomUUID()
        assertThat(
            event(
                OrderEvents.ROUTE_TAX_RATE_VERSION_CREATION, OrderEvents.TAX_RATE_VERSION_CREATED,
                """{"id":"$versionId","rate":$rate,"version":1,"createdAt":"2026-01-01T00:00:00Z","taxRateId":"$taxRateId"}"""
            )
        ).isEqualTo(200)
        return taxRateId to versionId
    }

    /** A product variant with a current version (catalog payload); returns the version id. */
    fun registerProductVariantVersion(productVariantId: UUID, price: Long, taxRateId: UUID, versionId: UUID = UUID.randomUUID()): UUID {
        assertThat(
            event(
                OrderEvents.ROUTE_PRODUCT_VARIANT_VERSION_CREATION, OrderEvents.PRODUCT_VARIANT_VERSION_CREATED,
                """{"id":"$versionId","name":"v","description":"d","version":1,"retailPrice":$price,"createdAt":"2026-01-01T00:00:00Z",
                   "canBeReturnedForDays":30,"productVariantId":"$productVariantId","taxRateId":"$taxRateId","weight":0.5,"mediaIds":[]}"""
            )
        ).isEqualTo(200)
        return versionId
    }

    /** Inserts an order directly (for query/pagination tests). */
    fun insertOrder(
        userId: UUID,
        createdAt: Instant = RustDateTime.now(),
        status: OrderStatus = OrderStatus.PENDING,
        items: Int = 1,
        id: UUID = UUID.randomUUID(),
    ): OrderRecord {
        val itemRecords = (0 until items).map {
            OrderItemRecord(
                id = UUID.randomUUID(), createdAt = createdAt, productVariantId = UUID.randomUUID(),
                productVariantIsPubliclyVisible = true,
                productVariantVersion = ProductVariantVersionRecord(UUID.randomUUID(), 20, UUID.randomUUID()),
                taxRateVersion = TaxRateVersionRecord(UUID.randomUUID(), 0.19, 1), shoppingCartItemId = UUID.randomUUID(),
                count = 1, compensatableAmount = 10L + it, shipmentMethodId = UUID.randomUUID(),
                discounts = listOf(DiscountRecord(UUID.randomUUID(), 1.0)),
            )
        }
        val order = OrderRecord(
            id = id, userId = userId, createdAt = createdAt, orderStatus = status,
            placedAt = if (status == OrderStatus.PLACED) createdAt else null, rejectionReason = null, items = itemRecords,
            shipmentAddressId = UUID.randomUUID(), invoiceAddressId = UUID.randomUUID(),
            compensatableOrderAmount = itemRecords.sumOf { it.compensatableAmount }, paymentInformationId = UUID.randomUUID(),
            vatNumber = null,
        )
        runBlocking { store.insertOrder(order) }
        return order
    }

    fun sql(statement: String) {
        runBlocking { databaseClient.sql(statement).fetch().awaitRowsUpdated() }
    }

    fun buyer(id: UUID) = authorizedUserHeader(id, "buyer")

    fun admin() = authorizedUserHeader(UUID.randomUUID(), "admin")
}
