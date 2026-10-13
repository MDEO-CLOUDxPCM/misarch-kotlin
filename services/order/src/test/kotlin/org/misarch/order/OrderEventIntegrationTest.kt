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
    fun `dapr subscribe equals the original's list`() {
        val body = webTestClient.get().uri("/dapr/subscribe").exchange()
            .expectStatus().isOk
            .expectBody(String::class.java).returnResult().responseBody!!
        val reference = File("../../reference/dapr-subscribe/order.json").readText()
        assertThat(mapper.readTree(body)).isEqualTo(mapper.readTree(reference))
        assertThat(body).isEqualTo(reference.trim())
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
    fun `id creation events insert replicas, duplicates and unexpected topics fail with 500`() {
        val userId = UUID.randomUUID()
        assertThat(event(OrderEvents.ROUTE_ID_CREATION, OrderEvents.USER_CREATED, """{"id":"$userId","username":"x"}""")).isEqualTo(200)
        assertThat(runBlocking { store.userExists(userId) }).isTrue()
        assertThat(runBlocking { store.userAddressIds(userId) }).isEmpty()
        // duplicate delivery -> insert fails -> 500 (Dapr redelivers)
        assertThat(event(OrderEvents.ROUTE_ID_CREATION, OrderEvents.USER_CREATED, """{"id":"$userId"}""")).isEqualTo(500)

        val couponId = registerCoupon()
        val shipmentMethodId = registerShipmentMethod()
        assertThat(runBlocking { store.existingIds("coupons", listOf(couponId, shipmentMethodId)) }).containsExactly(couponId)
        assertThat(runBlocking { store.existingIds("shipment_methods", listOf(couponId, shipmentMethodId)) }).containsExactly(shipmentMethodId)
        assertThat(event(OrderEvents.ROUTE_ID_CREATION, OrderEvents.COUPON_CREATED, """{"id":"$couponId"}""")).isEqualTo(500)

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
    fun `product variant version events create and update the replica, visibility updates fail with 422`() {
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

        // the real catalog payload is {"id","publiclyVisible":bool}; the original expects `isPubliclyVisible: String`
        // -> missing field -> 422 (Dapr redelivers), nothing changes
        assertThat(
            event(OrderEvents.ROUTE_PRODUCT_VARIANT_UPDATED, OrderEvents.PRODUCT_VARIANT_UPDATED, """{"id":"$productVariantId","publiclyVisible":false}""")
        ).isEqualTo(422)
        // a boolean under the expected key is rejected as well
        assertThat(
            event(OrderEvents.ROUTE_PRODUCT_VARIANT_UPDATED, OrderEvents.PRODUCT_VARIANT_UPDATED, """{"id":"$productVariantId","isPubliclyVisible":false}""")
        ).isEqualTo(422)
        assertThat(runBlocking { store.findProductVariant(productVariantId) }!!.let { it.isPubliclyVisible to it.visibilityRaw })
            .isEqualTo(true to null)

        // a string would be $set and corrupt the replica: later version events fail (insert path, duplicate)
        assertThat(
            event(OrderEvents.ROUTE_PRODUCT_VARIANT_UPDATED, OrderEvents.PRODUCT_VARIANT_UPDATED, """{"id":"$productVariantId","isPubliclyVisible":"false"}""")
        ).isEqualTo(200)
        assertThat(runBlocking { store.findProductVariant(productVariantId) }!!.visibilityRaw).isEqualTo("false")
        assertThat(
            event(
                OrderEvents.ROUTE_PRODUCT_VARIANT_VERSION_CREATION, OrderEvents.PRODUCT_VARIANT_VERSION_CREATED,
                """{"id":"${UUID.randomUUID()}","retailPrice":30,"taxRateId":"$taxRateId","productVariantId":"$productVariantId"}"""
            )
        ).isEqualTo(500)
        // unknown variant: no upsert, still 200
        assertThat(
            event(OrderEvents.ROUTE_PRODUCT_VARIANT_UPDATED, OrderEvents.PRODUCT_VARIANT_UPDATED, """{"id":"${UUID.randomUUID()}","isPubliclyVisible":"true"}""")
        ).isEqualTo(200)
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
    fun `user address events push and pull address ids`() {
        val userId = registerUser()
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        fun created(id: UUID) = event(OrderEvents.ROUTE_USER_ADDRESS_CREATION, OrderEvents.USER_ADDRESS_CREATED,
            """{"id":"$id","name":null,"street1":"s","street2":"","city":"c","postalCode":"p","country":"DE","companyName":null,"userId":"$userId"}""")
        assertThat(created(a)).isEqualTo(200)
        assertThat(created(b)).isEqualTo(200)
        assertThat(created(a)).isEqualTo(200) // redelivery appends again ($push)
        assertThat(runBlocking { store.userAddressIds(userId) }).containsExactly(a, b, a)
        assertThat(event(OrderEvents.ROUTE_USER_ADDRESS_ARCHIVED, OrderEvents.USER_ADDRESS_ARCHIVED, """{"id":"$a","userId":"$userId"}""")).isEqualTo(200)
        assertThat(runBlocking { store.userAddressIds(userId) }).containsExactly(b) // $pull removes all occurrences
        // unknown user: no upsert, 200
        val unknown = UUID.randomUUID()
        assertThat(event(OrderEvents.ROUTE_USER_ADDRESS_CREATION, OrderEvents.USER_ADDRESS_CREATED, """{"id":"$a","userId":"$unknown"}""")).isEqualTo(200)
        assertThat(runBlocking { store.userExists(unknown) }).isFalse()
        // the archive event of the address service carries only id and userId
        assertThat(event(OrderEvents.ROUTE_USER_ADDRESS_ARCHIVED, OrderEvents.USER_ADDRESS_ARCHIVED, """{"id":"$a"}""")).isEqualTo(422)
    }

    @Test
    fun `shipment creation failed route compensates with the original's inverted check`() {
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
        assertThat(events[0].body).isEqualTo("""{"id":"${compensation.id}","amount_to_compensate":${compensation.amountToCompensate}}""")

        // unrelated item: an existing compensation shares no item with it -> 500 (inverted check)
        assertThat(failed(order.id, listOf(i3.id))).isEqualTo(500)
        // the same items again: every existing compensation overlaps -> compensated again
        assertThat(failed(order.id, listOf(i1.id))).isEqualTo(200)
        assertThat(runBlocking { store.compensationsOfOrder(order.id) }).hasSize(2)
        // unknown order -> 500; a return shipment (orderId null) -> 422
        assertThat(failed(UUID.randomUUID(), listOf(i1.id))).isEqualTo(500)
        assertThat(
            event(OrderEvents.ROUTE_SHIPMENT_CREATION_FAILED, OrderEvents.SHIPMENT_CREATION_FAILED, """{"orderId":null,"orderItemIds":[]}""")
        ).isEqualTo(422)
        sql("DELETE FROM order_compensations")
    }
}
