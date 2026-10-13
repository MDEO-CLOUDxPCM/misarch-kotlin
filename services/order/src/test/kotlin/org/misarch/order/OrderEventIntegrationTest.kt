package org.misarch.order

import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.misarch.order.event.OrderEvents
import java.io.File
import java.util.UUID

/**
 * Dapr side of the order service: `/dapr/subscribe`, every subscription route (status codes, replicas), the unsubscribed
 * compensation route and its event.
 */
class OrderEventIntegrationTest : OrderIntegrationTestBase() {

    @Test
    fun `dapr subscribe equals the original's list plus the shipment creation-failed subscription`() {
        val body = webTestClient.get().uri("/dapr/subscribe").exchange()
            .expectStatus().isOk
            .expectBody(String::class.java).returnResult().responseBody!!
        // FIXED (docs/fixes/order.md): the compensation route is subscribed (the original served it unsubscribed)
        val reference = File("../../reference/dapr-subscribe/order.json").readText().trim()
        val expected = reference.removeSuffix("]") +
            """,{"pubsubName":"pubsub","topic":"shipment/shipment/creation-failed","route":"/on-shipment-creation-failed-event"}]"""
        assertThat(body).isEqualTo(expected)
    }

    @Test
    fun `captured CloudEvents of the running originals are accepted on their routes`() {
        val captured = mapOf(
            "user_user_created" to OrderEvents.ROUTE_ID_CREATION,
            "discount_coupon_created" to OrderEvents.ROUTE_ID_CREATION,
            "shipment_shipment-method_created" to OrderEvents.ROUTE_ID_CREATION,
            "tax_tax-rate-version_created" to OrderEvents.ROUTE_TAX_RATE_VERSION_CREATION,
            "catalog_product-variant-version_created" to OrderEvents.ROUTE_PRODUCT_VARIANT_VERSION_CREATION,
            "address_user-address_created" to OrderEvents.ROUTE_USER_ADDRESS_CREATION,
        )
        captured.forEach { (file, route) ->
            val cloudEvent = File("../../reference/events/$file.json").readText()
            val status = webTestClient.post().uri(route)
                .header("Content-Type", "application/cloudevents+json").bodyValue(cloudEvent)
                .exchange().returnResult(String::class.java).status.value()
            assertThat(status).describedAs(file).isEqualTo(200)
        }
        val pvv = mapper.readTree(File("../../reference/events/catalog_product-variant-version_created.json"))["data"]
        val replica = runBlocking { store.findProductVariant(UUID.fromString(pvv["productVariantId"].asString())) }!!
        assertThat(replica.currentVersion.price).isEqualTo(pvv["retailPrice"].asLong())
        assertThat(replica.currentVersion.taxRateId.toString()).isEqualTo(pvv["taxRateId"].asString())
    }

    @Test
    fun `health answers 200`() {
        webTestClient.get().uri("/health").exchange().expectStatus().isOk.expectBody().isEmpty()
    }

    @Test
    fun `id creation events insert replicas, duplicates are acknowledged, unexpected topics fail with 500`() {
        val userId = UUID.randomUUID()
        assertThat(event(OrderEvents.ROUTE_ID_CREATION, OrderEvents.USER_CREATED, """{"id":"$userId","username":"x"}""")).isEqualTo(200)
        assertThat(runBlocking { store.userExists(userId) }).isTrue()
        assertThat(runBlocking { store.userAddressIds(userId) }).isEmpty()
        // FIXED: a duplicate delivery is acknowledged without change (was 500, redelivered forever)
        assertThat(event(OrderEvents.ROUTE_ID_CREATION, OrderEvents.USER_CREATED, """{"id":"$userId"}""")).isEqualTo(200)
        assertThat(runBlocking { store.userAddressIds(userId) }).isEmpty()

        val couponId = registerCoupon()
        val shipmentMethodId = registerShipmentMethod()
        assertThat(runBlocking { store.existingIds("coupons", listOf(couponId, shipmentMethodId)) }).containsExactly(couponId)
        assertThat(runBlocking { store.existingIds("shipment_methods", listOf(couponId, shipmentMethodId)) }).containsExactly(shipmentMethodId)
        assertThat(event(OrderEvents.ROUTE_ID_CREATION, OrderEvents.COUPON_CREATED, """{"id":"$couponId"}""")).isEqualTo(200)

        // a topic the route does not handle
        val other = UUID.randomUUID()
        assertThat(event(OrderEvents.ROUTE_ID_CREATION, "catalog/product/created", """{"id":"$other"}""")).isEqualTo(500)
        assertThat(event(OrderEvents.ROUTE_USER_ADDRESS_CREATION, OrderEvents.USER_CREATED, """{"id":"$other","userId":"$other"}""")).isEqualTo(500)
        assertThat(runBlocking { store.userExists(other) }).isFalse()
    }

    @Test
    fun `malformed deliveries are rejected like axum does`() {
        val response = webTestClient.post().uri(OrderEvents.ROUTE_ID_CREATION)
            .header("Content-Type", "text/plain").bodyValue("""{"topic":"user/user/created","data":{"id":"${UUID.randomUUID()}"}}""")
            .exchange().returnResult(String::class.java)
        assertThat(response.status.value()).isEqualTo(415)
        val okJson = webTestClient.post().uri(OrderEvents.ROUTE_ID_CREATION)
            .header("Content-Type", "application/json").bodyValue("""{"topic":"user/user/created","data":{"id":"${UUID.randomUUID()}"}}""")
            .exchange().returnResult(String::class.java)
        assertThat(okJson.status.value()).isEqualTo(200)
        assertThat(okJson.responseBody.blockFirst()).isEqualTo("""{"status":0}""")
        val syntax = webTestClient.post().uri(OrderEvents.ROUTE_ID_CREATION)
            .header("Content-Type", "application/cloudevents+json").bodyValue("""{"topic":""")
            .exchange().returnResult(String::class.java)
        assertThat(syntax.status.value()).isEqualTo(400)
        assertThat(event(OrderEvents.ROUTE_ID_CREATION, OrderEvents.USER_CREATED, """{"name":"no id"}""")).isEqualTo(422)
        assertThat(event(OrderEvents.ROUTE_ID_CREATION, OrderEvents.USER_CREATED, """{"id":"not-a-uuid"}""")).isEqualTo(422)
        val noTopic = webTestClient.post().uri(OrderEvents.ROUTE_ID_CREATION)
            .header("Content-Type", "application/cloudevents+json").bodyValue("""{"data":{"id":"${UUID.randomUUID()}"}}""")
            .exchange().returnResult(String::class.java)
        assertThat(noTopic.status.value()).isEqualTo(422)
    }

    @Test
    fun `product variant version events create and update the replica, visibility updates are replicated`() {
        val productVariantId = UUID.randomUUID()
        val (taxRateId, _) = registerTaxRate()
        val v1 = registerProductVariantVersion(productVariantId, 20, taxRateId)
        val created = runBlocking { store.findProductVariant(productVariantId) }!!
        assertThat(created.currentVersion.id).isEqualTo(v1)
        assertThat(created.currentVersion.price).isEqualTo(20)
        assertThat(created.isPubliclyVisible).isTrue()

        val v2 = registerProductVariantVersion(productVariantId, 25, taxRateId)
        val updated = runBlocking { store.findProductVariant(productVariantId) }!!
        assertThat(updated.currentVersion.id).isEqualTo(v2)
        assertThat(updated.currentVersion.price).isEqualTo(25)
        // an older version delivered later wins (no version check)
        registerProductVariantVersion(productVariantId, 21, taxRateId, v1)
        assertThat(runBlocking { store.findProductVariant(productVariantId) }!!.currentVersion.id).isEqualTo(v1)

        // retailPrice out of u32 range -> 422
        assertThat(
            event(
                OrderEvents.ROUTE_PRODUCT_VARIANT_VERSION_CREATION, OrderEvents.PRODUCT_VARIANT_VERSION_CREATED,
                """{"id":"${UUID.randomUUID()}","retailPrice":-1,"taxRateId":"$taxRateId","productVariantId":"$productVariantId"}"""
            )
        ).isEqualTo(422)

        // FIXED: the real catalog payload {"id","publiclyVisible":bool} is replicated (the original answered 422 forever)
        fun visible() = runBlocking { store.findProductVariant(productVariantId) }!!.isPubliclyVisible
        assertThat(
            event(OrderEvents.ROUTE_PRODUCT_VARIANT_UPDATED, OrderEvents.PRODUCT_VARIANT_UPDATED, """{"id":"$productVariantId","publiclyVisible":false}""")
        ).isEqualTo(200)
        assertThat(visible()).isFalse()
        // the documented key, as a boolean or a string
        assertThat(
            event(OrderEvents.ROUTE_PRODUCT_VARIANT_UPDATED, OrderEvents.PRODUCT_VARIANT_UPDATED, """{"id":"$productVariantId","isPubliclyVisible":true}""")
        ).isEqualTo(200)
        assertThat(visible()).isTrue()
        assertThat(
            event(OrderEvents.ROUTE_PRODUCT_VARIANT_UPDATED, OrderEvents.PRODUCT_VARIANT_UPDATED, """{"id":"$productVariantId","isPubliclyVisible":"false"}""")
        ).isEqualTo(200)
        assertThat(visible()).isFalse()
        assertThat(
            event(OrderEvents.ROUTE_PRODUCT_VARIANT_UPDATED, OrderEvents.PRODUCT_VARIANT_UPDATED, """{"id":"$productVariantId","publiclyVisible":"maybe"}""")
        ).isEqualTo(422)
        // a later version keeps the visibility
        assertThat(
            event(
                OrderEvents.ROUTE_PRODUCT_VARIANT_VERSION_CREATION, OrderEvents.PRODUCT_VARIANT_VERSION_CREATED,
                """{"id":"${UUID.randomUUID()}","retailPrice":30,"taxRateId":"$taxRateId","productVariantId":"$productVariantId"}"""
            )
        ).isEqualTo(200)
        assertThat(visible()).isFalse()
        assertThat(runBlocking { store.findProductVariant(productVariantId) }!!.currentVersion.price).isEqualTo(30)
        // unknown variant: no upsert, still 200
        val unknownVariant = UUID.randomUUID()
        assertThat(
            event(OrderEvents.ROUTE_PRODUCT_VARIANT_UPDATED, OrderEvents.PRODUCT_VARIANT_UPDATED, """{"id":"$unknownVariant","publiclyVisible":true}""")
        ).isEqualTo(200)
        assertThat(runBlocking { store.findProductVariant(unknownVariant) }).isNull()
    }

    @Test
    fun `tax rate version events upsert the current version`() {
        val (taxRateId, v1) = registerTaxRate(0.19)
        assertThat(runBlocking { store.findTaxRates(listOf(taxRateId)) }.single().currentVersion.id).isEqualTo(v1)
        val v2 = UUID.randomUUID()
        assertThat(
            event(
                OrderEvents.ROUTE_TAX_RATE_VERSION_CREATION, OrderEvents.TAX_RATE_VERSION_CREATED,
                """{"id":"$v2","rate":0.07,"version":2,"createdAt":"x","taxRateId":"$taxRateId"}"""
            )
        ).isEqualTo(200)
        val current = runBlocking { store.findTaxRates(listOf(taxRateId)) }.single().currentVersion
        assertThat(current.id).isEqualTo(v2)
        assertThat(current.rate).isEqualTo(0.07)
        assertThat(current.version).isEqualTo(2)
        assertThat(
            event(OrderEvents.ROUTE_TAX_RATE_VERSION_CREATION, OrderEvents.TAX_RATE_VERSION_CREATED, """{"id":"$v2","rate":"x","version":2,"taxRateId":"$taxRateId"}""")
        ).isEqualTo(422)
    }

    @Test
    fun `user address events add (idempotently) and pull address ids`() {
        val userId = registerUser()
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        fun created(id: UUID) = event(OrderEvents.ROUTE_USER_ADDRESS_CREATION, OrderEvents.USER_ADDRESS_CREATED,
            """{"id":"$id","name":null,"street1":"s","street2":"","city":"c","postalCode":"p","country":"DE","companyName":null,"userId":"$userId"}""")
        assertThat(created(a)).isEqualTo(200)
        assertThat(created(b)).isEqualTo(200)
        assertThat(created(a)).isEqualTo(200) // FIXED: a redelivery does not append again
        assertThat(runBlocking { store.userAddressIds(userId) }).containsExactly(a, b)
        assertThat(event(OrderEvents.ROUTE_USER_ADDRESS_ARCHIVED, OrderEvents.USER_ADDRESS_ARCHIVED, """{"id":"$a","userId":"$userId"}""")).isEqualTo(200)
        assertThat(runBlocking { store.userAddressIds(userId) }).containsExactly(b)
        // FIXED: an address event before the user event creates the user replica; the user event then keeps it
        val early = UUID.randomUUID()
        assertThat(event(OrderEvents.ROUTE_USER_ADDRESS_CREATION, OrderEvents.USER_ADDRESS_CREATED, """{"id":"$a","userId":"$early"}""")).isEqualTo(200)
        assertThat(event(OrderEvents.ROUTE_ID_CREATION, OrderEvents.USER_CREATED, """{"id":"$early"}""")).isEqualTo(200)
        assertThat(runBlocking { store.userAddressIds(early) }).containsExactly(a)
        // archive of an unknown user: no-op, 200
        val unknown = UUID.randomUUID()
        assertThat(event(OrderEvents.ROUTE_USER_ADDRESS_ARCHIVED, OrderEvents.USER_ADDRESS_ARCHIVED, """{"id":"$a","userId":"$unknown"}""")).isEqualTo(200)
        assertThat(runBlocking { store.userExists(unknown) }).isFalse()
        // the archive event of the address service carries only id and userId
        assertThat(event(OrderEvents.ROUTE_USER_ADDRESS_ARCHIVED, OrderEvents.USER_ADDRESS_ARCHIVED, """{"id":"$a"}""")).isEqualTo(422)
    }

    @Test
    fun `shipment creation failed compensates each item once`() {
        sql("DELETE FROM order_compensations")
        val userId = UUID.randomUUID()
        val order = insertOrder(userId, items = 3)
        val (i1, i2, i3) = order.items
        fun failed(orderId: UUID, items: List<UUID>) = event(
            OrderEvents.ROUTE_SHIPMENT_CREATION_FAILED, OrderEvents.SHIPMENT_CREATION_FAILED,
            """{"orderId":"$orderId","returnId":null,"orderItemIds":[${items.joinToString(",") { "\"$it\"" }}],
               "shipmentMethodId":"${UUID.randomUUID()}","shipmentAddressId":"${UUID.randomUUID()}","reason":"x"}"""
        )
        assertThat(failed(order.id, listOf(i1.id, i2.id))).isEqualTo(200)
        val events = dapr.awaitPublished(OrderEvents.ORDER_COMPENSATION_CREATED)
        assertThat(events).hasSize(1)
        val compensation = runBlocking { store.compensationsOfOrder(order.id) }.single()
        assertThat(compensation.amountToCompensate).isEqualTo(i1.compensatableAmount + i2.compensatableAmount)
        assertThat(compensation.orderItemIds).containsExactly(i1.id, i2.id)
        val body = mapper.readTree(events[0].body)
        assertThat(body.propertyNames().asSequence().toList())
            .containsExactly("id", "orderId", "orderItemIds", "triggeredAt", "amountToCompensate")
        assertThat(body["id"].asString()).isEqualTo(compensation.id.toString())
        assertThat(body["orderId"].asString()).isEqualTo(order.id.toString())
        assertThat(body["orderItemIds"].toList().map { it.asString() }).containsExactly(i1.id.toString(), i2.id.toString())
        assertThat(body["amountToCompensate"].asLong()).isEqualTo(compensation.amountToCompensate)

        // FIXED: an unrelated item is compensated (the original's inverted check answered 500 forever)
        assertThat(failed(order.id, listOf(i3.id))).isEqualTo(200)
        assertThat(runBlocking { store.compensationsOfOrder(order.id) }).hasSize(2)
        // FIXED: already compensated items are not compensated again (redelivery), 200
        dapr.reset()
        assertThat(failed(order.id, listOf(i1.id, i3.id))).isEqualTo(200)
        assertThat(runBlocking { store.compensationsOfOrder(order.id) }).hasSize(2)
        assertThat(dapr.published(OrderEvents.ORDER_COMPENSATION_CREATED)).isEmpty()
        // unknown order -> 500; FIXED: a failed return shipment (orderId null or missing) is acknowledged
        assertThat(failed(UUID.randomUUID(), listOf(i1.id))).isEqualTo(500)
        assertThat(
            event(OrderEvents.ROUTE_SHIPMENT_CREATION_FAILED, OrderEvents.SHIPMENT_CREATION_FAILED, """{"orderId":null,"returnId":"${UUID.randomUUID()}","orderItemIds":[]}""")
        ).isEqualTo(200)
        assertThat(
            event(OrderEvents.ROUTE_SHIPMENT_CREATION_FAILED, OrderEvents.SHIPMENT_CREATION_FAILED, """{"returnId":"${UUID.randomUUID()}","orderItemIds":[]}""")
        ).isEqualTo(200)
        sql("DELETE FROM order_compensations")
    }
}
