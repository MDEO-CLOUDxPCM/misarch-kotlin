package org.misarch.order

import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.misarch.common.testing.FakeDaprSidecar.StubResponse
import org.misarch.order.event.OrderEvents
import org.misarch.order.graphql.model.OrderStatus
import org.misarch.order.remote.RemoteServices
import org.misarch.common.rust.RustDateTime
import tools.jackson.databind.JsonNode
import java.time.Instant
import java.util.UUID

/**
 * GraphQL side of the order service against Postgres and the fake Dapr sidecar: createOrder with its 4 invocations,
 * placeOrder with the published event, queries, pagination, federation entities and authorization.
 */
class OrderGraphQLIntegrationTest : OrderIntegrationTestBase() {

    private val dateTime = Regex("""^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(\.\d{3})?\+00:00$""")

    /** A user with replicas for a checkout: 2 cart lines (variants with prices 20 and 30), a coupon, a shipment method. */
    inner class Checkout {
        val userId = registerUser()
        val shipmentMethodId = registerShipmentMethod()
        val couponId = registerCoupon()
        val tax = registerTaxRate(0.19)
        val pv1 = UUID.randomUUID()
        val pv2 = UUID.randomUUID()
        val pvv1 = registerProductVariantVersion(pv1, 20, tax.first)
        val pvv2 = registerProductVariantVersion(pv2, 30, tax.first)
        val cart1 = UUID.randomUUID()
        val cart2 = UUID.randomUUID()
        val discountId = UUID.randomUUID()
        val addressId = UUID.randomUUID()
        val paymentInformationId = UUID.randomUUID()

        fun stubs(
            cart: Map<UUID, Pair<UUID, Long>> = mapOf(cart1 to (pv1 to 2L), cart2 to (pv2 to 1L)),
            stock: Map<UUID, Long> = mapOf(pv1 to 100L, pv2 to 100L),
            discounts: Map<UUID, List<Pair<UUID, Double>>> = mapOf(pv1 to listOf(discountId to 0.5), pv2 to emptyList()),
            fees: Long = 5,
        ) = CheckoutStubs(cart, stock, discounts, fees).also { it.install(dapr) }

        fun input(
            items: List<Map<String, Any?>> = listOf(
                mapOf("shoppingCartItemId" to cart1.toString(), "shipmentMethodId" to shipmentMethodId.toString(), "couponIds" to listOf(couponId.toString())),
                mapOf("shoppingCartItemId" to cart2.toString(), "shipmentMethodId" to shipmentMethodId.toString(), "couponIds" to emptyList<String>()),
            ),
            userId: UUID = this.userId,
        ) = mapOf(
            "userId" to userId.toString(), "orderItemInputs" to items, "shipmentAddressId" to addressId.toString(),
            "invoiceAddressId" to addressId.toString(), "paymentInformationId" to paymentInformationId.toString(),
            "vatNumber" to "AB1234",
        )

        fun create(input: Map<String, Any?> = input(), user: String? = buyer(userId)): JsonNode = graphql(
            """
            mutation(${'$'}input: CreateOrderInput!) { createOrder(input: ${'$'}input) {
              id orderStatus createdAt placedAt rejectionReason compensatableOrderAmount paymentInformationId
              user { id } invoiceAddress { id }
              orderItems { totalCount hasNextPage nodes { id createdAt count compensatableAmount productVariant { id }
                productVariantVersion { id } taxRateVersion { id } shoppingCartItem { id } shipmentMethod { id }
                discounts { totalCount nodes { id } } } }
            } }
            """.trimIndent(),
            mapOf("input" to input), user
        )

        fun ordersInDb() = runBlocking { store.countOrdersOfUser(userId) }
    }

    private fun place(orderId: UUID, user: String?, cvc: Int? = 123): JsonNode = graphql(
        """mutation(${'$'}input: PlaceOrderInput!) { placeOrder(input: ${'$'}input) { id orderStatus placedAt } }""",
        mapOf("input" to mapOf("id" to orderId.toString(), "paymentAuthorization" to cvc?.let { mapOf("cvc" to it) })),
        user
    )

    private fun JsonNode.error(): String = this["errors"][0]["message"].asString()

    @Test
    fun `createOrder makes the 4 invocations in order and stores the order`() {
        val c = Checkout()
        c.stubs()
        val result = c.create()
        assertThat(result["errors"]).isNull()
        val order = result["data"]["createOrder"]
        assertThat(order["orderStatus"].asString()).isEqualTo("PENDING")
        assertThat(order["createdAt"].asString()).matches(dateTime.toPattern())
        assertThat(order["placedAt"].isNull).isTrue()
        assertThat(order["rejectionReason"].isNull).isTrue()
        // pv1: 20 x 0.5 (discount fraction, count ignored) = 10; pv2: 30
        assertThat(order["compensatableOrderAmount"].asInt()).isEqualTo(40)
        assertThat(order["user"]["id"].asString()).isEqualTo(c.userId.toString())
        assertThat(order["invoiceAddress"]["id"].asString()).isEqualTo(c.addressId.toString())
        val items = order["orderItems"]
        assertThat(items["totalCount"].asInt()).isEqualTo(2)
        val byVariant = (0 until 2).associate { items["nodes"][it]["productVariant"]["id"].asString() to items["nodes"][it] }
        val item1 = byVariant.getValue(c.pv1.toString())
        assertThat(item1["count"].asInt()).isEqualTo(2)
        assertThat(item1["compensatableAmount"].asInt()).isEqualTo(10)
        assertThat(item1["productVariantVersion"]["id"].asString()).isEqualTo(c.pvv1.toString())
        assertThat(item1["taxRateVersion"]["id"].asString()).isEqualTo(c.tax.second.toString())
        assertThat(item1["shoppingCartItem"]["id"].asString()).isEqualTo(c.cart1.toString())
        assertThat(item1["shipmentMethod"]["id"].asString()).isEqualTo(c.shipmentMethodId.toString())
        assertThat(item1["discounts"]["nodes"][0]["id"].asString()).isEqualTo(c.discountId.toString())
        assertThat(item1["createdAt"].asString()).isEqualTo(order["createdAt"].asString())
        assertThat(byVariant.getValue(c.pv2.toString())["compensatableAmount"].asInt()).isEqualTo(30)

        // the 4 invocations, sequentially, in the original's order
        assertThat(dapr.invocations.map { it.appId to it.path }).containsExactly(
            "shoppingcart" to "", "inventory" to "graphql", "discount" to "graphql", "shipment" to "graphql"
        )
        dapr.invocations.forEach {
            assertThat(it.method).isEqualTo("POST")
            assertThat(it.headers["content-type"]).startsWith("application/json")
        }
        val (cart, inventory, discount, shipment) = dapr.invocations.map { mapper.readTree(it.body) }
        assertThat(dapr.invocations[0].headers["authorized-user"]).isEqualTo("""{"id":"${c.userId}","roles":["buyer"]}""")
        assertThat(dapr.invocations.drop(1).map { it.headers["authorized-user"] }).containsOnlyNulls()
        assertThat(cart.propertyNames().asSequence().toList()).containsExactly("variables", "query", "operationName")
        assertThat(cart["query"].asString()).isEqualTo(RemoteServices.SHOPPING_CART_QUERY)
        assertThat(cart["operationName"].asString()).isEqualTo("GetShoppingCartProductVariantIdsAndCounts")
        assertThat(cart["variables"].toString()).isEqualTo("""{"representations":[{"__typename":"User","id":"${c.userId}"}]}""")
        assertThat(inventory["query"].asString()).isEqualTo(RemoteServices.INVENTORY_QUERY)
        assertThat(inventory["operationName"].asString()).isEqualTo("GetUnreservedProductItemCounts")
        assertThat(inventory["variables"]["representations"].toString()).isIn(
            """[{"__typename":"ProductVariant","id":"${c.pv1}"},{"__typename":"ProductVariant","id":"${c.pv2}"}]""",
            """[{"__typename":"ProductVariant","id":"${c.pv2}"},{"__typename":"ProductVariant","id":"${c.pv1}"}]""",
        )
        assertThat(discount["query"].asString()).isEqualTo(RemoteServices.DISCOUNT_QUERY)
        assertThat(discount["operationName"].asString()).isEqualTo("GetDiscounts")
        val discountInput = discount["variables"]["findApplicableDiscountsInput"]
        assertThat(discountInput["orderAmount"].asLong()).isEqualTo(50) // unit prices, not multiplied by count
        assertThat(discountInput["userId"].asString()).isEqualTo(c.userId.toString())
        val discountVariants = (0 until 2).associate { discountInput["productVariants"][it]["productVariantId"].asString() to discountInput["productVariants"][it] }
        assertThat(discountVariants.getValue(c.pv1.toString()).toString())
            .isEqualTo("""{"count":2,"couponIds":["${c.couponId}"],"productVariantId":"${c.pv1}"}""")
        assertThat(discountVariants.getValue(c.pv2.toString()).toString())
            .isEqualTo("""{"count":1,"couponIds":[],"productVariantId":"${c.pv2}"}""")
        assertThat(shipment["query"].asString()).isEqualTo(RemoteServices.SHIPMENT_QUERY)
        assertThat(shipment["operationName"].asString()).isEqualTo("GetShipmentFees")
        val shipmentItems = shipment["variables"]["calculateShipmentFeesInput"]["items"]
        assertThat((0 until 2).map { shipmentItems[it].toString() }).containsExactlyInAnyOrder(
            """{"productVariantVersionId":"${c.pvv1}","quantity":2,"shipmentMethodId":"${c.shipmentMethodId}"}""",
            """{"productVariantVersionId":"${c.pvv2}","quantity":1,"shipmentMethodId":"${c.shipmentMethodId}"}""",
        )

        // stored like the document of the original
        val stored = runBlocking { store.findOrder(UUID.fromString(order["id"].asString())) }!!
        assertThat(stored.vatNumber).isEqualTo("AB1234")
        assertThat(stored.shipmentAddressId).isEqualTo(c.addressId)
        assertThat(stored.items.first { it.productVariantId == c.pv1 }.taxRateVersion.rate).isEqualTo(0.19)
        assertThat(stored.createdAt.nano % 1_000_000).isEqualTo(0)
        // nothing was published by createOrder
        assertThat(dapr.published).isEmpty()
    }

    @Test
    fun `createOrder accepts a single input object and an admin may order for another user`() {
        val c = Checkout()
        c.stubs(cart = mapOf(c.cart1 to (c.pv1 to 1L)), discounts = mapOf(c.pv1 to emptyList()))
        val admin = UUID.randomUUID()
        val adminHeader = authorizedUserHeader(admin, "admin")
        val single = c.input().toMutableMap().apply {
            put("orderItemInputs", mapOf("shoppingCartItemId" to c.cart1.toString(), "shipmentMethodId" to c.shipmentMethodId.toString(), "couponIds" to emptyList<String>()))
        }
        val result = c.create(single, adminHeader)
        assertThat(result["errors"]).isNull()
        assertThat(result["data"]["createOrder"]["compensatableOrderAmount"].asInt()).isEqualTo(20)
        // the caller's header is forwarded to the shoppingcart
        assertThat(dapr.invocations[0].headers["authorized-user"]).isEqualTo("""{"id":"$admin","roles":["admin"]}""")
    }

    @Test
    fun `createOrder validation and remote failures in the original's order, nothing is persisted`() {
        val c = Checkout()
        c.stubs()
        // authorization first
        assertThat(c.create(user = null).error()).isEqualTo("Authentication failed. Authorized-User header is not set or could not be parsed.")
        assertThat(c.create(user = "garbage").error()).isEqualTo("Authentication failed. Authorized-User header is not set or could not be parsed.")
        val other = UUID.randomUUID()
        assertThat(c.create(user = buyer(other)).error()).isEqualTo("Authentication failed for user of UUID: `$other`. Operation not permitted.")
        assertThat(c.create(user = authorizedUserHeader(c.userId)).data()).isNotNull() // own id without roles is permitted
        sql("DELETE FROM order_item_discounts WHERE order_item_id IN (SELECT i.id FROM order_items i JOIN orders o ON o.id = i.order_id WHERE o.user_id = '${c.userId}')")
        sql("DELETE FROM order_items WHERE order_id IN (SELECT id FROM orders WHERE user_id = '${c.userId}')")
        sql("DELETE FROM orders WHERE user_id = '${c.userId}'")
        dapr.invocations.clear()

        // unknown user (replica)
        val unknownUser = UUID.randomUUID()
        assertThat(c.create(c.input(userId = unknownUser), admin()).error())
            .isEqualTo("misarch_order::graphql::model::user::User with UUID: `$unknownUser` not found.")
        // unknown shipment method / coupon: the last missing id is reported
        val sm = UUID.randomUUID()
        val coupon = UUID.randomUUID()
        val badItems = listOf(
            mapOf("shoppingCartItemId" to c.cart1.toString(), "shipmentMethodId" to sm.toString(), "couponIds" to listOf(coupon.toString())),
        )
        assertThat(c.create(c.input(badItems)).error())
            .isEqualTo("misarch_order::graphql::model::foreign_types::ShipmentMethod with UUID: `$sm` is not present in the system.")
        val badCoupon = listOf(
            mapOf("shoppingCartItemId" to c.cart1.toString(), "shipmentMethodId" to c.shipmentMethodId.toString(), "couponIds" to listOf(coupon.toString())),
        )
        assertThat(c.create(c.input(badCoupon)).error())
            .isEqualTo("misarch_order::graphql::model::foreign_types::Coupon with UUID: `$coupon` is not present in the system.")
        assertThat(dapr.invocations).isEmpty() // local validation happens before the first invocation

        // cart item not in the cart
        val missingCartItem = UUID.randomUUID()
        val notInCart = listOf(
            mapOf("shoppingCartItemId" to missingCartItem.toString(), "shipmentMethodId" to c.shipmentMethodId.toString(), "couponIds" to emptyList<String>()),
        )
        assertThat(c.create(c.input(notInCart)).error()).isEqualTo(
            "`(bson::uuid::Uuid, u64)` for product variant of UUID: `$missingCartItem` is not present in " +
                "`std::collections::hash::map::HashMap<bson::uuid::Uuid, (bson::uuid::Uuid, u64)>`. "
        )

        // insufficient stock / variant missing in the inventory answer
        dapr.reset(); c.stubs(stock = mapOf(c.pv1 to 1L, c.pv2 to 100L))
        assertThat(c.create().error()).isEqualTo("Not all requested product variants are available.")
        assertThat(dapr.invocations.map { it.appId }).containsExactly("shoppingcart", "inventory")
        dapr.reset(); c.stubs(stock = mapOf(c.pv2 to 100L))
        assertThat(c.create().error()).isEqualTo(
            "Response data of `check_product_variant_availability` query could not be parsed, `maybe_product_variant_enum` is `None`"
        )
        dapr.reset(); c.stubs(stock = mapOf(c.pv1 to -1L, c.pv2 to 100L))
        assertThat(c.create().error()).isEqualTo("out of range integral type conversion attempted")

        // discount answer without a variant
        dapr.reset(); c.stubs(discounts = mapOf(c.pv1 to emptyList()))
        assertThat(c.create().error()).isEqualTo(
            "Product variant of UUID: `${c.pv2}` is not contained in the result which `findApplicableDiscounts` provides."
        )
        // negative shipment fees
        dapr.reset(); c.stubs(fees = -1)
        assertThat(c.create().error()).isEqualTo("out of range integral type conversion attempted")
        // data null / not JSON / no stub (Dapr answers an error document)
        dapr.reset(); c.stubs(); dapr.stubInvocation("shipment", "graphql") { StubResponse(body = """{"data":null,"errors":[{"message":"x"}]}""") }
        assertThat(c.create().error()).isEqualTo("Response data of `query_shipment_fees` query is empty.")
        dapr.reset(); c.stubs(); dapr.stubInvocation("discount", "graphql") { StubResponse(status = 500, body = "oops", contentType = "text/plain") }
        assertThat(c.create().error()).isEqualTo("error decoding response body")
        dapr.reset(); c.stubs(); dapr.stubInvocation("shoppingcart", "") { StubResponse(status = 500, body = """{"errorCode":"ERR_DIRECT_INVOKE"}""") }
        assertThat(c.create().error()).isEqualTo("Response data of `query_counts_by_product_variant_ids` query is empty.")
        dapr.reset(); c.stubs(); dapr.stubInvocation("shoppingcart", "") { StubResponse(body = """{"data":{"_entities":[null]}}""") }
        assertThat(c.create().error()).isEqualTo("Response data of `query_counts_by_product_variant_ids` query is empty.")

        // missing tax rate replica
        val pv3 = UUID.randomUUID()
        registerProductVariantVersion(pv3, 10, UUID.randomUUID())
        dapr.reset(); c.stubs(cart = mapOf(c.cart1 to (pv3 to 1L)), stock = mapOf(pv3 to 5L), discounts = mapOf(pv3 to emptyList()))
        val onlyCart1 = listOf(mapOf("shoppingCartItemId" to c.cart1.toString(), "shipmentMethodId" to c.shipmentMethodId.toString(), "couponIds" to emptyList<String>()))
        assertThat(c.create(c.input(onlyCart1)).error()).isEqualTo(
            "`misarch_order::graphql::model::foreign_types::TaxRate` for product variant of UUID: `$pv3` is not present in " +
                "`std::collections::hash::map::HashMap<bson::uuid::Uuid, misarch_order::graphql::model::foreign_types::TaxRate>`. "
        )
        assertThat(c.ordersInDb()).isEqualTo(0)
    }

    @Test
    fun `unreplicated product variants are silently dropped, an order can have zero items`() {
        val c = Checkout()
        val unknownPv = UUID.randomUUID()
        c.stubs(
            cart = mapOf(c.cart1 to (c.pv1 to 1L), c.cart2 to (unknownPv to 1L)),
            stock = mapOf(c.pv1 to 1L, unknownPv to 1L),
            discounts = mapOf(c.pv1 to emptyList(), unknownPv to emptyList()),
        )
        val result = c.create()
        assertThat(result["errors"]).isNull()
        assertThat(result["data"]["createOrder"]["orderItems"]["totalCount"].asInt()).isEqualTo(1)
        // inventory and discount were asked for both, shipment only for the replicated one
        val (_, inventory, discount, shipment) = dapr.invocations.map { mapper.readTree(it.body) }
        assertThat(inventory["variables"]["representations"].size()).isEqualTo(2)
        assertThat(discount["variables"]["findApplicableDiscountsInput"]["productVariants"].size()).isEqualTo(2)
        assertThat(discount["variables"]["findApplicableDiscountsInput"]["orderAmount"].asLong()).isEqualTo(20)
        assertThat(shipment["variables"]["calculateShipmentFeesInput"]["items"].size()).isEqualTo(1)

        dapr.reset()
        c.stubs(cart = emptyMap(), stock = emptyMap(), discounts = emptyMap())
        val empty = c.create(c.input(emptyList()))
        assertThat(empty["errors"]).isNull()
        assertThat(empty["data"]["createOrder"]["orderItems"]["totalCount"].asInt()).isEqualTo(0)
        assertThat(empty["data"]["createOrder"]["compensatableOrderAmount"].asInt()).isEqualTo(0)
        assertThat(dapr.invocations).hasSize(4)
    }

    @Test
    fun `placeOrder places, publishes the exact OrderDTO and returns the order`() {
        val c = Checkout()
        c.stubs()
        val orderId = UUID.fromString(c.create()["data"]["createOrder"]["id"].asString())
        // async-graphql range check of the cvc (u16) happens before anything else
        assertThat(place(orderId, buyer(c.userId), 70000).error()).isEqualTo(
            "Failed to parse \"Int\": Only integers from 0 to 65535 are accepted. (occurred while parsing \"PaymentAuthorizationInput\") (occurred while parsing \"PlaceOrderInput\")"
        )
        val other = UUID.randomUUID()
        assertThat(place(orderId, buyer(other)).error()).isEqualTo("Authentication failed for user of UUID: `$other`. Operation not permitted.")
        val missing = UUID.randomUUID()
        assertThat(place(missing, buyer(c.userId)).error()).isEqualTo("misarch_order::graphql::model::order::Order with UUID: `$missing` not found.")

        val result = place(orderId, buyer(c.userId))
        assertThat(result["errors"]).isNull()
        assertThat(result["data"]["placeOrder"]["orderStatus"].asString()).isEqualTo("PLACED")
        assertThat(result["data"]["placeOrder"]["placedAt"].asString()).matches(dateTime.toPattern())

        val events = dapr.awaitPublished(OrderEvents.ORDER_CREATED)
        assertThat(events).hasSize(1)
        assertThat(events[0].pubsubName).isEqualTo("pubsub")
        assertThat(events[0].contentType).startsWith("application/json")
        val stored = runBlocking { store.findOrder(orderId) }!!
        val item1 = stored.items.first { it.productVariantId == c.pv1 }
        val item2 = stored.items.first { it.productVariantId == c.pv2 }
        fun itemJson(item: org.misarch.order.persistence.model.OrderItemRecord, discounts: List<UUID>) =
            """{"id":"${item.id}","createdAt":"${RustDateTime.toSerde(item.createdAt)}","productVariantId":"${item.productVariantId}",""" +
                """"productVariantVersionId":"${item.productVariantVersion.id}","taxRateVersionId":"${item.taxRateVersion.id}",""" +
                """"shoppingCartItemId":"${item.shoppingCartItemId}","count":${item.count},"compensatableAmount":${item.compensatableAmount},""" +
                """"shipmentMethodId":"${item.shipmentMethodId}","discountIds":[${discounts.joinToString(",") { "\"$it\"" }}]}"""
        val items = stored.items.joinToString(",") { if (it == item1) itemJson(item1, listOf(c.discountId)) else itemJson(item2, emptyList()) }
        assertThat(events[0].body).isEqualTo(
            """{"id":"$orderId","userId":"${c.userId}","createdAt":"${RustDateTime.toSerde(stored.createdAt)}","orderStatus":"PLACED",""" +
                """"placedAt":"${RustDateTime.toSerde(stored.placedAt!!)}","rejectionReason":null,"orderItems":[$items],""" +
                """"shipmentAddressId":"${c.addressId}","invoiceAddressId":"${c.addressId}","compensatableOrderAmount":40,""" +
                """"paymentInformationId":"${c.paymentInformationId}","paymentAuthorization":{"cVC":123},"vatNumber":"AB1234"}"""
        )
        assertThat(RustDateTime.toRfc3339(stored.placedAt!!)).isEqualTo(result["data"]["placeOrder"]["placedAt"].asString())

        // already placed
        assertThat(place(orderId, buyer(c.userId)).error()).isEqualTo(
            "`Placed` must be `OrderStatus::Pending` to be able to be placed. Order was already placed or rejected."
        )
        assertThat(dapr.published(OrderEvents.ORDER_CREATED)).hasSize(1)
    }

    @Test
    fun `placeOrder without cvc publishes null, ignores the sidecar's status, rejects after one hour`() {
        val userId = registerUser()
        val order = insertOrder(userId, items = 2)
        dapr.publishStatus = 500 // the original ignores the HTTP status of the publish
        val placed = place(order.id, admin(), cvc = null)
        assertThat(placed["errors"]).isNull()
        val event = dapr.awaitPublished(OrderEvents.ORDER_CREATED).single().json
        assertThat(event["paymentAuthorization"].isNull).isTrue()
        assertThat(event["vatNumber"].isNull).isTrue()
        assertThat(event["orderItems"].size()).isEqualTo(2)
        // paymentAuthorization without cvc
        val second = insertOrder(userId)
        assertThat(graphql("""mutation { placeOrder(input: {id: "${second.id}", paymentAuthorization: {}}) { id } }""", user = buyer(userId))["errors"]).isNull()
        assertThat(dapr.awaitPublished(OrderEvents.ORDER_CREATED, 2)[1].json["paymentAuthorization"].isNull).isTrue()

        // pending for more than 1 h -> REJECTED (no rejection reason) + error
        val old = insertOrder(userId, createdAt = RustDateTime.now().minusSeconds(3601))
        assertThat(place(old.id, buyer(userId)).error())
            .isEqualTo("Order of id: `${old.id}` was rejected as it is `OrderStatus::Pending` for too long.")
        val rejected = runBlocking { store.findOrder(old.id) }!!
        assertThat(rejected.orderStatus).isEqualTo(OrderStatus.REJECTED)
        assertThat(rejected.rejectionReason).isNull()
        // an already PLACED order older than 1 h is set to REJECTED as well
        val oldPlaced = insertOrder(userId, createdAt = RustDateTime.now().minusSeconds(7200), status = OrderStatus.PLACED)
        assertThat(place(oldPlaced.id, buyer(userId)).error()).contains("was rejected")
        assertThat(runBlocking { store.findOrder(oldPlaced.id) }!!.orderStatus).isEqualTo(OrderStatus.REJECTED)
        assertThat(place(old.id, buyer(userId)).error()).contains("was rejected")
        assertThat(dapr.published(OrderEvents.ORDER_CREATED)).hasSize(2)
    }

    @Test
    fun `order query authorizes after loading, orderItem always fails`() {
        val userId = UUID.randomUUID()
        val order = insertOrder(userId, items = 3)
        val query = """{ order(id: "${order.id}") { id orderStatus createdAt compensatableOrderAmount paymentInformationId
            orderItems(first: 2, skip: 0, orderBy: {direction: DESC}) { totalCount hasNextPage nodes { id discounts { totalCount nodes { id } } } } } }"""
        assertThat(graphql(query)["errors"][0]["message"].asString())
            .isEqualTo("Authentication failed. Authorized-User header is not set or could not be parsed.")
        val other = UUID.randomUUID()
        assertThat(graphql(query, user = buyer(other)).error()).isEqualTo("Authentication failed for user of UUID: `$other`. Operation not permitted.")
        val own = graphql(query, user = buyer(userId))
        assertThat(own["errors"]).isNull()
        val connection = own["data"]["order"]["orderItems"]
        assertThat(connection["totalCount"].asInt()).isEqualTo(3)
        assertThat(connection["hasNextPage"].asBoolean()).isTrue()
        val expectedIds = order.items.map { it.id }.sortedWith(org.misarch.common.rust.RustUuid.ORDER).reversed().take(2)
        assertThat((0 until 2).map { UUID.fromString(connection["nodes"][it]["id"].asString()) }).containsExactlyElementsOf(expectedIds)
        assertThat(graphql(query, user = authorizedUserHeader(other, "employee"))["errors"]).isNull()
        val missing = UUID.randomUUID()
        assertThat(graphql("""{ order(id: "$missing") { id } }""").error())
            .isEqualTo("misarch_order::graphql::model::order::Order with UUID: `$missing` not found.")
        // order_items is never written in the original -> every lookup fails
        val itemId = order.items[0].id
        assertThat(graphql("""{ orderItem(id: "$itemId") { id } }""", user = admin()).error())
            .isEqualTo("misarch_order::graphql::model::order_item::OrderItem with UUID: `$itemId` not found.")
        // UUID forms of uuid::parse_str
        val simple = order.id.toString().replace("-", "")
        assertThat(graphql("""{ order(id: "$simple") { id } }""", user = admin())["data"]["order"]["id"].asString()).isEqualTo(order.id.toString())
    }

    @Test
    fun `federation entities`() {
        val userId = registerUser()
        val order = insertOrder(userId)
        val unknownUser = UUID.randomUUID()
        val result = graphql(
            """
            query(${'$'}r: [_Any!]!) { _entities(representations: ${'$'}r) {
              ... on Order { id orderStatus user { id } }
              ... on User { id }
              ... on OrderItem { id }
            } }
            """.trimIndent(),
            mapOf(
                "r" to listOf(
                    mapOf("__typename" to "Order", "id" to order.id.toString()),
                    mapOf("__typename" to "User", "id" to userId.toString()),
                    mapOf("__typename" to "User", "id" to unknownUser.toString()),
                    mapOf("__typename" to "OrderItem", "id" to order.items[0].id.toString()),
                )
            )
        )
        // async-graphql (original, observed in the deployment, docs/INTEGRATION.md F4): `_entities` is all-or-nothing,
        // the first failing representation nulls `data` and is the only error, with path ["_entities"]
        assertThat(result.has("data")).isTrue()
        assertThat(result["data"].isNull).isTrue()
        assertThat(result["errors"].size()).isEqualTo(1)
        assertThat(result["errors"][0]["message"].asString())
            .isEqualTo("misarch_order::graphql::model::user::User with UUID: `$unknownUser` not found.")
        assertThat(result["errors"][0]["path"].toString()).isEqualTo("""["_entities"]""")
        val ok = graphql(
            """query(${'$'}r: [_Any!]!) { _entities(representations: ${'$'}r) { ... on Order { id orderStatus user { id } } ... on User { id } } }""",
            mapOf("r" to listOf(mapOf("__typename" to "Order", "id" to order.id.toString()), mapOf("__typename" to "User", "id" to userId.toString())))
        )
        assertThat(ok["errors"]).isNull()
        assertThat(ok["data"]["_entities"][0]["id"].asString()).isEqualTo(order.id.toString()) // no authorization
        assertThat(ok["data"]["_entities"][1]["id"].asString()).isEqualTo(userId.toString())
    }

    @Test
    fun `a failing root field answers data null like async-graphql`() {
        val missing = UUID.randomUUID()
        val result = graphql("""{ order(id: "$missing") { id } }""")
        assertThat(result.has("data")).isTrue()
        assertThat(result["data"].isNull).isTrue()
        assertThat(result["errors"][0]["path"].toString()).isEqualTo("""["order"]""")
    }

    @Test
    fun `User orders pages like mongodb-cursor-pagination`() {
        val userId = registerUser()
        val base = Instant.parse("2026-01-01T00:00:00Z")
        val orders = (0 until 27).map { insertOrder(userId, createdAt = base.plusSeconds((it % 5).toLong())) }
        insertOrder(UUID.randomUUID()) // another user's order
        fun page(args: String, user: String? = buyer(userId)): JsonNode = graphql(
            """query(${'$'}r: [_Any!]!) { _entities(representations: ${'$'}r) { ... on User { orders$args { totalCount hasNextPage nodes { id } } } } }""",
            mapOf("r" to listOf(mapOf("__typename" to "User", "id" to userId.toString()))), user
        )
        fun ids(result: JsonNode): List<UUID> {
            val nodes = result["data"]["_entities"][0]["orders"]["nodes"]
            return (0 until nodes.size()).map { UUID.fromString(nodes[it]["id"].asString()) }
        }
        val byIdAsc = orders.map { it.id }.sortedWith(org.misarch.common.rust.RustUuid.ORDER)

        // default: 25 per page, id ASC
        val first = page("")
        assertThat(first["errors"]).isNull()
        assertThat(first["data"]["_entities"][0]["orders"]["totalCount"].asInt()).isEqualTo(27)
        assertThat(first["data"]["_entities"][0]["orders"]["hasNextPage"].asBoolean()).isTrue()
        assertThat(ids(first)).containsExactlyElementsOf(byIdAsc.take(25))
        // first/skip
        val p = page("(first: 5, skip: 5)")
        assertThat(ids(p)).containsExactlyElementsOf(byIdAsc.subList(5, 10))
        assertThat(p["data"]["_entities"][0]["orders"]["hasNextPage"].asBoolean()).isTrue()
        // skip + first + 1 == totalCount: first + 1 nodes and no next page (library quirk)
        val quirk = page("(first: 5, skip: 21)")
        assertThat(ids(quirk)).containsExactlyElementsOf(byIdAsc.subList(21, 27))
        assertThat(quirk["data"]["_entities"][0]["orders"]["hasNextPage"].asBoolean()).isFalse()
        // without skip the extra row is dropped
        val last = page("(first: 26)")
        assertThat(ids(last)).hasSize(26)
        assertThat(last["data"]["_entities"][0]["orders"]["hasNextPage"].asBoolean()).isTrue()
        assertThat(ids(page("(first: 27)"))).hasSize(27)
        // ordering
        assertThat(ids(page("(first: 30, orderBy: {direction: DESC})"))).containsExactlyElementsOf(byIdAsc.reversed())
        val byCreatedAt = orders.sortedWith(compareBy<org.misarch.order.persistence.model.OrderRecord> { it.createdAt }
            .thenComparator { a, b -> org.misarch.common.rust.RustUuid.ORDER.compare(b.id, a.id) }).map { it.id }
        assertThat(ids(page("(first: 30, orderBy: {field: CREATED_AT})"))).containsExactlyElementsOf(byCreatedAt)
        for (field in listOf("NAME", "LAST_UPDATED_AT", "USER_ID")) {
            assertThat(ids(page("(first: 30, orderBy: {field: $field, direction: ASC})"))).containsExactlyElementsOf(byIdAsc.reversed())
        }
        // authorization and arguments
        val other = UUID.randomUUID()
        assertThat(page("", buyer(other)).error()).isEqualTo("Authentication failed for user of UUID: `$other`. Operation not permitted.")
        assertThat(page("", authorizedUserHeader(other, "employee"))["errors"]).isNull()
        assertThat(page("(first: -1)").error()).isEqualTo("Failed to parse \"Int\": Invalid number")
        // a user without orders
        val lonely = registerUser()
        val none = graphql(
            """query(${'$'}r: [_Any!]!) { _entities(representations: ${'$'}r) { ... on User { orders { totalCount hasNextPage nodes { id } } } } }""",
            mapOf("r" to listOf(mapOf("__typename" to "User", "id" to lonely.toString()))), buyer(lonely)
        )
        assertThat(none["data"]["_entities"][0]["orders"].toString()).isEqualTo("""{"totalCount":0,"hasNextPage":false,"nodes":[]}""")
        // Order.user.orders resolves through the embedded user
        val nested = graphql("""{ order(id: "${orders[0].id}") { user { orders(first: 1) { totalCount } } } }""", user = buyer(userId))
        assertThat(nested["data"]["order"]["user"]["orders"]["totalCount"].asInt()).isEqualTo(27)
    }

    private fun JsonNode.data(): JsonNode? = this["data"]?.takeUnless { it.isNull }
}
