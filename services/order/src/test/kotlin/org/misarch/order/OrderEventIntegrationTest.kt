package org.misarch.order

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.misarch.common.persistence.requiredId
import org.misarch.order.event.OrderEvents
import org.springframework.test.json.JsonCompareMode
import java.io.File
import java.util.UUID

/**
 * The Dapr side of the order service: `/dapr/subscribe`, the replica events and the compensation of a failed shipment.
 */
class OrderEventIntegrationTest : OrderTestSupport() {

    private val referenceDir = File(System.getProperty("misarch.reference.dir"))

    @Test
    fun `dapr subscribe lists every consumed topic on its own route`() {
        val body = webTestClient.get().uri("/dapr/subscribe").exchange()
            .expectStatus().isOk
            .expectBody(String::class.java).returnResult().responseBody!!
        val subscriptions = mapper.readTree(body).toList().map { "${it["pubsubName"].asString()} ${it["topic"].asString()} ${it["route"].asString()}" }
        // the reference topics plus shipment/shipment/creation-failed
        val topics = mapper.readTree(File(referenceDir, "dapr-subscribe/order.json")).toList().map { it["topic"].asString() } +
            OrderEvents.SHIPMENT_CREATION_FAILED
        assertThat(subscriptions).containsExactlyInAnyOrderElementsOf(topics.map { "pubsub $it /subscription/$it" })
    }

    @Test
    fun `health endpoint is UP`() {
        webTestClient.get().uri("/health").exchange()
            .expectStatus().isOk
            .expectBody().json("""{"status":"UP"}""", JsonCompareMode.STRICT)
    }

    @Test
    fun `the CloudEvents captured from the running services are accepted`() {
        val captured = listOf(
            "user_user_created", "discount_coupon_created", "shipment_shipment-method_created", "tax_tax-rate-version_created",
            "catalog_product-variant-version_created", "address_user-address_created",
        )
        captured.forEach { file ->
            val cloudEvent = File(referenceDir, "events/$file.json").readText()
            val topic = mapper.readTree(cloudEvent)["topic"].asString()
            val status = webTestClient.post().uri("/subscription/$topic")
                .header("Content-Type", "application/cloudevents+json").bodyValue(cloudEvent)
                .exchange().returnResult(String::class.java).status.value()
            assertThat(status).describedAs(file).isEqualTo(200)
        }
        val version = mapper.readTree(File(referenceDir, "events/catalog_product-variant-version_created.json"))["data"]
        val replica = sql("SELECT * FROM ProductVariantEntity WHERE id = '${version["productVariantId"].asString()}'").single()
        assertThat(replica["currentversionprice"]).isEqualTo(version["retailPrice"].asLong())
        assertThat(replica["currentversiontaxrateid"].toString()).isEqualTo(version["taxRateId"].asString())
    }

    @Test
    fun `replica events insert the ids, redeliveries are acknowledged without change`() {
        val userId = registerUser()
        registerUser(userId)
        assertThat(sql("SELECT id FROM UserEntity WHERE id = '$userId'")).hasSize(1)
        val couponId = registerCoupon()
        registerCoupon(couponId)
        assertThat(sql("SELECT id FROM CouponEntity WHERE id = '$couponId'")).hasSize(1)
        val shipmentMethodId = registerShipmentMethod()
        registerShipmentMethod(shipmentMethodId)
        assertThat(sql("SELECT id FROM ShipmentMethodEntity WHERE id = '$shipmentMethodId'")).hasSize(1)
        assertThat(deliverEvent("/subscription/user/user/created", "user/user/created", """{"name":"no id"}""")).isEqualTo(400)
    }

    @Test
    fun `product variant version events set the current version, visibility updates are replicated`() {
        val productVariantId = UUID.randomUUID()
        val (taxRateId, _) = registerTaxRate()
        fun replica() = sql("SELECT * FROM ProductVariantEntity WHERE id = '$productVariantId'").single()
        val v1 = registerProductVariantVersion(productVariantId, 20, taxRateId)
        assertThat(replica()["currentversionid"]).isEqualTo(v1)
        assertThat(replica()["currentversionprice"]).isEqualTo(20L)
        assertThat(replica()["ispubliclyvisible"]).isEqualTo(true)
        val v2 = registerProductVariantVersion(productVariantId, 25, taxRateId)
        assertThat(replica()["currentversionid"]).isEqualTo(v2)
        assertThat(replica()["currentversionprice"]).isEqualTo(25L)

        // catalog publishes `publiclyVisible`; the documented key `isPubliclyVisible` is accepted too
        deliverOk("catalog/product-variant/updated", """{"id":"$productVariantId","publiclyVisible":false}""")
        assertThat(replica()["ispubliclyvisible"]).isEqualTo(false)
        deliverOk("catalog/product-variant/updated", """{"id":"$productVariantId","isPubliclyVisible":true}""")
        assertThat(replica()["ispubliclyvisible"]).isEqualTo(true)
        deliverOk("catalog/product-variant/updated", """{"id":"$productVariantId","publiclyVisible":false}""")
        // a new version keeps the visibility
        registerProductVariantVersion(productVariantId, 30, taxRateId)
        assertThat(replica()["ispubliclyvisible"]).isEqualTo(false)
        assertThat(replica()["currentversionprice"]).isEqualTo(30L)
        // an unknown product variant is not created by an update
        val unknown = UUID.randomUUID()
        deliverOk("catalog/product-variant/updated", """{"id":"$unknown","publiclyVisible":true}""")
        assertThat(sql("SELECT id FROM ProductVariantEntity WHERE id = '$unknown'")).isEmpty()
    }

    @Test
    fun `tax rate version events set the current version`() {
        val (taxRateId, v1) = registerTaxRate(0.19)
        assertThat(sql("SELECT currentVersionId FROM TaxRateEntity WHERE id = '$taxRateId'").single()["currentversionid"]).isEqualTo(v1)
        val v2 = UUID.randomUUID()
        deliverOk("tax/tax-rate-version/created", """{"id":"$v2","rate":0.07,"version":2,"createdAt":"x","taxRateId":"$taxRateId"}""")
        val current = sql("SELECT * FROM TaxRateEntity WHERE id = '$taxRateId'").single()
        assertThat(current["currentversionid"]).isEqualTo(v2)
        assertThat(current["currentversionrate"]).isEqualTo(0.07)
        assertThat(current["currentversionversion"]).isEqualTo(2L)
    }

    @Test
    fun `user address events add and remove addresses of the user`() {
        val userId = registerUser()
        fun addresses(user: UUID) = sql("SELECT id FROM UserAddressEntity WHERE userId = '$user'").map { it["id"] }
        val a = registerUserAddress(userId)
        val b = registerUserAddress(userId)
        registerUserAddress(userId, a)
        assertThat(addresses(userId)).containsExactlyInAnyOrder(a, b)
        archiveUserAddress(userId, a)
        assertThat(addresses(userId)).containsExactly(b)
        // an address event before the user event registers the user
        val early = UUID.randomUUID()
        registerUserAddress(early, a)
        registerUser(early)
        assertThat(addresses(early)).containsExactly(a)
        // archiving an unknown address is acknowledged
        archiveUserAddress(UUID.randomUUID(), a)
    }

    @Test
    fun `a failed shipment compensates each order item once`() {
        val order = insertOrder(UUID.randomUUID(), items = 3)
        val (i1, i2, i3) = order.items
        fun failed(orderId: UUID?, items: List<UUID>) = deliverEvent(
            "/subscription/${OrderEvents.SHIPMENT_CREATION_FAILED}", OrderEvents.SHIPMENT_CREATION_FAILED,
            """{"orderId":${orderId?.let { "\"$it\"" }},"returnId":null,"orderItemIds":[${items.joinToString(",") { "\"$it\"" }}],
               "shipmentMethodId":"${UUID.randomUUID()}","shipmentAddressId":"${UUID.randomUUID()}","reason":"x"}"""
        )
        fun compensations() = sql("SELECT * FROM OrderCompensationEntity WHERE orderId = '${order.id}'")
        fun compensatedItems(compensationId: Any?) =
            sql("SELECT orderItemId FROM OrderCompensationToOrderItemEntity WHERE orderCompensationId = '$compensationId' ORDER BY position")
                .map { it["orderitemid"] }

        assertThat(failed(order.id, listOf(i1.requiredId, i2.requiredId))).isEqualTo(200)
        val compensation = compensations().single()
        assertThat(compensation["amounttocompensate"]).isEqualTo(i1.compensatableAmount + i2.compensatableAmount)
        assertThat(compensatedItems(compensation["id"])).containsExactly(i1.id, i2.id)
        val event = dapr.awaitPublished(OrderEvents.ORDER_COMPENSATION_CREATED).single()
        val body = event.json
        assertThat(body.propertyNames().asSequence().toList())
            .containsExactly("id", "orderId", "orderItemIds", "triggeredAt", "amountToCompensate")
        assertThat(body["id"].asString()).isEqualTo(compensation["id"].toString())
        assertThat(body["orderId"].asString()).isEqualTo(order.id.toString())
        assertThat(body["orderItemIds"].toList().map { it.asString() }).containsExactly(i1.id.toString(), i2.id.toString())
        assertThat(body["amountToCompensate"].asLong()).isEqualTo(21)

        // another item is compensated, already compensated items are not compensated again
        assertThat(failed(order.id, listOf(i3.requiredId))).isEqualTo(200)
        assertThat(compensations()).hasSize(2)
        dapr.reset()
        assertThat(failed(order.id, listOf(i1.requiredId, i3.requiredId))).isEqualTo(200)
        assertThat(compensations()).hasSize(2)
        assertThat(dapr.published).isEmpty()
        // unknown order: fails (redelivered); a failed return shipment (no order) is acknowledged
        assertThat(failed(UUID.randomUUID(), listOf(i1.requiredId))).isEqualTo(500)
        assertThat(failed(null, emptyList())).isEqualTo(200)
        // a failed publish rolls the compensation back
        val other = insertOrder(UUID.randomUUID())
        dapr.publishStatus = 500
        assertThat(failed(other.id, listOf(other.items[0].requiredId))).isEqualTo(500)
        assertThat(sql("SELECT id FROM OrderCompensationEntity WHERE orderId = '${other.id}'")).isEmpty()
    }

}
