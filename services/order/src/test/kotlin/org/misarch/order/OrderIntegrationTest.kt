package org.misarch.order

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.misarch.common.testing.FakeDaprSidecar.StubResponse
import org.misarch.order.event.OrderEvents
import org.misarch.order.graphql.model.OrderStatus
import org.misarch.order.persistence.model.OrderItemEntity
import org.misarch.order.remote.CheckoutClient
import tools.jackson.databind.JsonNode
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.Executors

/**
 * The GraphQL side of the order service against a real Postgres (Testcontainers) and a fake Dapr sidecar:
 * createOrder with its four service invocations, placeOrder with the published event, queries, connections,
 * federation and authorization.
 */
class OrderIntegrationTest : OrderTestSupport() {

    /** A user with the replicas of a checkout: 2 cart lines (prices 20 and 30), a coupon, a shipment method, an address. */
    inner class Checkout {
        val userId = registerUser()
        val shipmentMethodId = registerShipmentMethod()
        val couponId = registerCoupon()
        val tax = registerTaxRate(0.19)
        val pv1: UUID = UUID.randomUUID()
        val pv2: UUID = UUID.randomUUID()
        val pvv1 = registerProductVariantVersion(pv1, 20, tax.first)
        val pvv2 = registerProductVariantVersion(pv2, 30, tax.first)
        val cart1: UUID = UUID.randomUUID()
        val cart2: UUID = UUID.randomUUID()
        val discountId: UUID = UUID.randomUUID()
        val addressId = registerUserAddress(userId)
        val paymentInformationId: UUID = UUID.randomUUID()

        fun stubs(
            cart: Map<UUID, Pair<UUID, Long>> = mapOf(cart1 to (pv1 to 2L), cart2 to (pv2 to 1L)),
            stock: Map<UUID, Long> = mapOf(pv1 to 100L, pv2 to 100L),
            discounts: Map<UUID, List<Pair<UUID, Double>>> = mapOf(pv1 to listOf(discountId to 0.5), pv2 to emptyList()),
            fees: Long = 5,
        ) = CheckoutStubs(cart, stock, discounts, fees).also { it.install(dapr) }

        fun item(cartItem: UUID, shipmentMethod: UUID = shipmentMethodId, coupons: List<UUID> = emptyList()) = mapOf(
            "shoppingCartItemId" to cartItem.toString(), "shipmentMethodId" to shipmentMethod.toString(),
            "couponIds" to coupons.map { it.toString() },
        )

        fun input(
            items: List<Map<String, Any?>> = listOf(item(cart1, coupons = listOf(couponId)), item(cart2)),
            userId: UUID = this.userId,
            shipmentAddressId: UUID = addressId,
            invoiceAddressId: UUID = addressId,
        ) = mapOf(
            "userId" to userId.toString(), "orderItemInputs" to items, "shipmentAddressId" to shipmentAddressId.toString(),
            "invoiceAddressId" to invoiceAddressId.toString(), "paymentInformationId" to paymentInformationId.toString(),
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

        fun ordersInDb(): Int = sql("SELECT id FROM OrderEntity WHERE userId = '$userId'").size
    }

    private fun place(orderId: UUID, user: String?, cvc: Int? = 123): JsonNode = graphql(
        """mutation(${'$'}input: PlaceOrderInput!) { placeOrder(input: ${'$'}input) { id orderStatus placedAt } }""",
        mapOf("input" to mapOf("id" to orderId.toString(), "paymentAuthorization" to cvc?.let { mapOf("cvc" to it) })),
        user
    )

    @Test
    fun `createOrder makes the four invocations in order and stores the order`() {
        val c = Checkout()
        c.stubs()
        val result = c.create()
        assertThat(result["errors"]).isNull()
        val order = result["data"]["createOrder"]
        assertThat(order["orderStatus"].asString()).isEqualTo("PENDING")
        assertThat(OffsetDateTime.parse(order["createdAt"].asString())).isNotNull()
        assertThat(order["placedAt"].isNull).isTrue()
        assertThat(order["rejectionReason"].isNull).isTrue()
        // pv1: 20 x 2 x (1 - 0.5) = 20; pv2: 30 x 1 = 30; + shipment fees 5
        assertThat(order["compensatableOrderAmount"].asInt()).isEqualTo(55)
        assertThat(order["user"]["id"].asString()).isEqualTo(c.userId.toString())
        assertThat(order["invoiceAddress"]["id"].asString()).isEqualTo(c.addressId.toString())
        val items = order["orderItems"]
        assertThat(items["totalCount"].asInt()).isEqualTo(2)
        val byVariant = items["nodes"].associateBy { it["productVariant"]["id"].asString() }
        val item1 = byVariant.getValue(c.pv1.toString())
        assertThat(item1["count"].asInt()).isEqualTo(2)
        assertThat(item1["compensatableAmount"].asInt()).isEqualTo(20)
        assertThat(item1["productVariantVersion"]["id"].asString()).isEqualTo(c.pvv1.toString())
        assertThat(item1["taxRateVersion"]["id"].asString()).isEqualTo(c.tax.second.toString())
        assertThat(item1["shoppingCartItem"]["id"].asString()).isEqualTo(c.cart1.toString())
        assertThat(item1["shipmentMethod"]["id"].asString()).isEqualTo(c.shipmentMethodId.toString())
        assertThat(item1["discounts"]["nodes"][0]["id"].asString()).isEqualTo(c.discountId.toString())
        assertThat(item1["createdAt"].asString()).isEqualTo(order["createdAt"].asString())
        assertThat(byVariant.getValue(c.pv2.toString())["compensatableAmount"].asInt()).isEqualTo(30)

        // the four invocations, one after the other
        assertThat(dapr.invocations.map { it.appId to it.path }).containsExactly(
            "shoppingcart" to "", "inventory" to "graphql", "discount" to "graphql", "shipment" to "graphql"
        )
        assertThat(dapr.invocations).allMatch { it.method == "POST" && it.headers["content-type"]!!.startsWith("application/json") }
        val (cart, inventory, discount, shipment) = dapr.invocations.map { mapper.readTree(it.body) }
        // the caller's Authorized-User header is forwarded to the shoppingcart only
        val forwarded = mapper.readTree(dapr.invocations[0].headers["authorized-user"])
        assertThat(forwarded["id"].asString()).isEqualTo(c.userId.toString())
        assertThat(forwarded["roles"].toList().map { it.asString() }).containsExactly("buyer")
        assertThat(dapr.invocations.drop(1).map { it.headers["authorized-user"] }).containsOnlyNulls()
        assertThat(cart["query"].asString()).isEqualTo(CheckoutClient.SHOPPING_CART_QUERY)
        assertThat(cart["variables"].toString()).isEqualTo("""{"representations":[{"__typename":"User","id":"${c.userId}"}]}""")
        assertThat(inventory["query"].asString()).isEqualTo(CheckoutClient.INVENTORY_QUERY)
        assertThat(inventory["variables"]["representations"].toList().map { it["id"].asString() })
            .containsExactly(c.pv1.toString(), c.pv2.toString())
        assertThat(discount["query"].asString()).isEqualTo(CheckoutClient.DISCOUNT_QUERY)
        val discountInput = discount["variables"]["findApplicableDiscountsInput"]
        assertThat(discountInput["orderAmount"].asInt()).isEqualTo(70) // 20 x 2 + 30 x 1
        assertThat(discountInput["userId"].asString()).isEqualTo(c.userId.toString())
        val discountVariants = discountInput["productVariants"].associateBy { it["productVariantId"].asString() }
        assertThat(discountVariants.getValue(c.pv1.toString())["count"].asInt()).isEqualTo(2)
        assertThat(discountVariants.getValue(c.pv1.toString())["couponIds"].toList().map { it.asString() }).containsExactly(c.couponId.toString())
        assertThat(discountVariants.getValue(c.pv2.toString())["couponIds"].size()).isEqualTo(0)
        assertThat(shipment["query"].asString()).isEqualTo(CheckoutClient.SHIPMENT_QUERY)
        assertThat(shipment["variables"]["calculateShipmentFeesInput"]["items"].toList().map { it.toString() }).containsExactlyInAnyOrder(
            """{"productVariantVersionId":"${c.pvv1}","quantity":2,"shipmentMethodId":"${c.shipmentMethodId}"}""",
            """{"productVariantVersionId":"${c.pvv2}","quantity":1,"shipmentMethodId":"${c.shipmentMethodId}"}""",
        )

        // stored with the item snapshots, in the order of the inputs
        val stored = findOrder(UUID.fromString(order["id"].asString()))!!
        assertThat(stored.vatNumber).isEqualTo("AB1234")
        assertThat(stored.shipmentAddressId).isEqualTo(c.addressId)
        assertThat(stored.createdAt.nano % 1_000_000).isEqualTo(0)
        val storedItems = findItems(stored.id!!)
        assertThat(storedItems.map { it.productVariantId }).containsExactly(c.pv1, c.pv2)
        assertThat(storedItems.map { it.taxRateVersionRate }).containsOnly(0.19)
        // nothing was published by createOrder
        assertThat(dapr.published).isEmpty()
    }

    @Test
    fun `an employee may order for another user, the last input of a product variant counts`() {
        val c = Checkout()
        val cart3 = UUID.randomUUID()
        val otherShipmentMethod = registerShipmentMethod()
        c.stubs(cart = mapOf(c.cart1 to (c.pv1 to 1L), cart3 to (c.pv1 to 3L)), discounts = mapOf(c.pv1 to emptyList()))
        val employeeId = UUID.randomUUID()
        val employee = authorizedUserHeader(employeeId, "employee")
        val result = c.create(c.input(listOf(c.item(c.cart1), c.item(cart3, otherShipmentMethod))), employee)
        assertThat(result["errors"]).isNull()
        assertThat(result["data"]["createOrder"]["compensatableOrderAmount"].asInt()).isEqualTo(65) // 20 x 3 + fees 5
        val item = result["data"]["createOrder"]["orderItems"]["nodes"].single()
        assertThat(item["shoppingCartItem"]["id"].asString()).isEqualTo(cart3.toString())
        assertThat(item["shipmentMethod"]["id"].asString()).isEqualTo(otherShipmentMethod.toString())
        assertThat(mapper.readTree(dapr.invocations[0].headers["authorized-user"])["id"].asString()).isEqualTo(employeeId.toString())
    }

    @Test
    fun `createOrder validates before the invocations and fails on their answers, nothing is stored`() {
        val c = Checkout()
        c.stubs()
        // authorization
        assertThat(errorMessage(c.create(user = null))).contains("Unauthorized access")
        val other = UUID.randomUUID()
        assertThat(errorMessage(c.create(user = buyer(other)))).contains("Unauthorized access: $other is not an employee or admin")
        // replicas
        val unknownUser = UUID.randomUUID()
        assertThat(errorMessage(c.create(c.input(userId = unknownUser), employee()))).endsWith("User with id $unknownUser does not exist")
        val shipmentMethod = UUID.randomUUID()
        assertThat(errorMessage(c.create(c.input(listOf(c.item(c.cart1, shipmentMethod))))))
            .endsWith("Shipment method with id $shipmentMethod does not exist")
        val coupon = UUID.randomUUID()
        assertThat(errorMessage(c.create(c.input(listOf(c.item(c.cart1, coupons = listOf(c.couponId, coupon)))))))
            .endsWith("Coupon with id $coupon does not exist")
        // the addresses must be addresses of the user, archived ones are no longer valid
        val foreignAddress = registerUserAddress(UUID.randomUUID())
        assertThat(errorMessage(c.create(c.input(shipmentAddressId = foreignAddress))))
            .endsWith("Address with id $foreignAddress is not an address of user ${c.userId}")
        val archived = registerUserAddress(c.userId)
        archiveUserAddress(c.userId, archived)
        assertThat(errorMessage(c.create(c.input(invoiceAddressId = archived))))
            .endsWith("Address with id $archived is not an address of user ${c.userId}")
        assertThat(dapr.invocations).isEmpty()

        // a cart item not in the cart
        val missingCartItem = UUID.randomUUID()
        assertThat(errorMessage(c.create(c.input(listOf(c.item(missingCartItem))))))
            .endsWith("Shopping cart item with id $missingCartItem is not in the shopping cart of user ${c.userId}")
        // insufficient stock, variant unknown to the inventory
        dapr.reset(); c.stubs(stock = mapOf(c.pv1 to 1L, c.pv2 to 100L))
        assertThat(errorMessage(c.create())).endsWith("Not all requested product variants are available")
        assertThat(dapr.invocations.map { it.appId }).containsExactly("shoppingcart", "inventory")
        dapr.reset(); c.stubs(stock = mapOf(c.pv2 to 100L))
        assertThat(errorMessage(c.create())).endsWith("Not all requested product variants are available")
        // discount answer without a variant
        dapr.reset(); c.stubs(discounts = mapOf(c.pv1 to emptyList()))
        assertThat(errorMessage(c.create())).endsWith("The discount service returned no discounts for product variant ${c.pv2}")
        // answers without data, failed invocations
        dapr.reset(); c.stubs(); dapr.stubInvocation("shipment", "graphql") { StubResponse(body = """{"data":null,"errors":[{"message":"x"}]}""") }
        assertThat(errorMessage(c.create())).contains("The shipment service answered without data")
        dapr.reset(); c.stubs(); dapr.stubInvocation("discount", "graphql") { StubResponse(status = 500, body = "oops", contentType = "text/plain") }
        assertThat(errorMessage(c.create())).contains("500")
        dapr.reset(); c.stubs(); dapr.stubInvocation("shoppingcart", "") { StubResponse(body = """{"data":{"_entities":[null]}}""") }
        assertThat(errorMessage(c.create())).endsWith("The shopping cart of user ${c.userId} was not found")
        // a product variant whose tax rate is not replicated
        val pv3 = UUID.randomUUID()
        val unknownTaxRate = UUID.randomUUID()
        registerProductVariantVersion(pv3, 10, unknownTaxRate)
        dapr.reset(); c.stubs(cart = mapOf(c.cart1 to (pv3 to 1L)), stock = mapOf(pv3 to 5L), discounts = mapOf(pv3 to emptyList()))
        assertThat(errorMessage(c.create(c.input(listOf(c.item(c.cart1))))))
            .endsWith("Tax rate with id $unknownTaxRate of product variant $pv3 does not exist")
        assertThat(c.ordersInDb()).isEqualTo(0)
    }

    @Test
    fun `unknown and invisible product variants are not ordered, an order can have no items`() {
        val c = Checkout()
        val unknownPv = UUID.randomUUID()
        deliverOk("catalog/product-variant/updated", """{"id":"${c.pv2}","publiclyVisible":false}""")
        val cart3 = UUID.randomUUID()
        c.stubs(
            cart = mapOf(c.cart1 to (c.pv1 to 1L), c.cart2 to (c.pv2 to 1L), cart3 to (unknownPv to 1L)),
            stock = mapOf(c.pv1 to 1L, c.pv2 to 1L, unknownPv to 1L),
            discounts = mapOf(c.pv1 to emptyList(), c.pv2 to emptyList(), unknownPv to emptyList()),
        )
        val result = c.create(c.input(listOf(c.item(c.cart1), c.item(c.cart2), c.item(cart3))))
        assertThat(result["errors"]).isNull()
        assertThat(result["data"]["createOrder"]["orderItems"]["totalCount"].asInt()).isEqualTo(1)
        assertThat(result["data"]["createOrder"]["compensatableOrderAmount"].asInt()).isEqualTo(25) // 20 + fees 5
        // inventory and discount are asked for all, shipment for the ordered one only
        val (_, inventory, discount, shipment) = dapr.invocations.map { mapper.readTree(it.body) }
        assertThat(inventory["variables"]["representations"].size()).isEqualTo(3)
        assertThat(discount["variables"]["findApplicableDiscountsInput"]["productVariants"].size()).isEqualTo(3)
        assertThat(discount["variables"]["findApplicableDiscountsInput"]["orderAmount"].asInt()).isEqualTo(20)
        assertThat(shipment["variables"]["calculateShipmentFeesInput"]["items"].size()).isEqualTo(1)

        dapr.reset()
        c.stubs(cart = emptyMap(), stock = emptyMap(), discounts = emptyMap())
        val empty = c.create(c.input(emptyList()))
        assertThat(empty["errors"]).isNull()
        assertThat(empty["data"]["createOrder"]["orderItems"]["totalCount"].asInt()).isEqualTo(0)
        assertThat(empty["data"]["createOrder"]["compensatableOrderAmount"].asInt()).isEqualTo(5)
        assertThat(dapr.invocations).hasSize(4)
    }

    @Test
    fun `placeOrder places the order and publishes it with its items and discounts`() {
        val c = Checkout()
        c.stubs()
        val orderId = UUID.fromString(c.create()["data"]["createOrder"]["id"].asString())
        val other = UUID.randomUUID()
        assertThat(errorMessage(place(orderId, buyer(other)))).contains("Unauthorized access: $other is not an employee or admin")
        assertThat(errorMessage(place(UUID.randomUUID(), buyer(c.userId)))).endsWith(": null")

        val result = place(orderId, buyer(c.userId))
        assertThat(result["errors"]).isNull()
        assertThat(result["data"]["placeOrder"]["orderStatus"].asString()).isEqualTo("PLACED")
        val stored = findOrder(orderId)!!
        assertThat(OffsetDateTime.parse(result["data"]["placeOrder"]["placedAt"].asString()).toInstant())
            .isEqualTo(stored.placedAt!!.toInstant())

        val events = dapr.awaitPublished(OrderEvents.ORDER_CREATED)
        assertThat(events).hasSize(1)
        assertThat(events[0].pubsubName).isEqualTo("pubsub")
        val (item1, item2) = findItems(orderId)
        fun itemJson(item: OrderItemEntity, discounts: List<UUID>) =
            """{"id":"${item.id}","createdAt":"${item.createdAt.toInstant()}","productVariantId":"${item.productVariantId}",""" +
                """"productVariantVersionId":"${item.productVariantVersionId}","taxRateVersionId":"${item.taxRateVersionId}",""" +
                """"shoppingCartItemId":"${item.shoppingCartItemId}","count":${item.count},"compensatableAmount":${item.compensatableAmount},""" +
                """"shipmentMethodId":"${item.shipmentMethodId}","discountIds":[${discounts.joinToString(",") { "\"$it\"" }}]}"""
        assertThat(events[0].body).isEqualTo(
            """{"id":"$orderId","userId":"${c.userId}","createdAt":"${stored.createdAt.toInstant()}","orderStatus":"PLACED",""" +
                """"placedAt":"${stored.placedAt!!.toInstant()}","rejectionReason":null,""" +
                """"orderItems":[${itemJson(item1, listOf(c.discountId))},${itemJson(item2, emptyList())}],""" +
                """"shipmentAddressId":"${c.addressId}","invoiceAddressId":"${c.addressId}","compensatableOrderAmount":55,""" +
                """"paymentInformationId":"${c.paymentInformationId}","paymentAuthorization":{"cvc":123},"vatNumber":"AB1234"}"""
        )

        // already placed
        assertThat(errorMessage(place(orderId, buyer(c.userId))))
            .endsWith("Order $orderId is not pending: it was already placed or rejected")
        assertThat(dapr.published(OrderEvents.ORDER_CREATED)).hasSize(1)
    }

    @Test
    fun `placeOrder without cvc publishes null, a failed publish keeps the order pending, rejects after one hour`() {
        val userId = registerUser()
        val order = insertOrder(userId, items = 2)
        // the publish is part of the placement: a failure leaves the order pending
        dapr.publishStatus = 500
        assertThat(place(order.id, employee(), cvc = null)["errors"]).isNotNull()
        assertThat(findOrder(order.id)!!.orderStatus).isEqualTo(OrderStatus.PENDING)
        dapr.reset()
        assertThat(place(order.id, employee(), cvc = null)["errors"]).isNull()
        val event = dapr.published(OrderEvents.ORDER_CREATED).last().json
        assertThat(event["paymentAuthorization"].isNull).isTrue()
        assertThat(event["vatNumber"].isNull).isTrue()
        assertThat(event["orderItems"].size()).isEqualTo(2)
        // paymentAuthorization without cvc
        val second = insertOrder(userId)
        val result = graphql("""mutation { placeOrder(input: {id: "${second.id}", paymentAuthorization: {}}) { id } }""", user = buyer(userId))
        assertThat(result["errors"]).isNull()
        assertThat(dapr.published(OrderEvents.ORDER_CREATED).last().json["paymentAuthorization"].isNull).isTrue()

        // pending for more than 1 h: rejected (without a rejection reason)
        val old = insertOrder(userId, createdAt = now().minusSeconds(3601))
        assertThat(errorMessage(place(old.id, buyer(userId))))
            .endsWith("Order ${old.id} was rejected as it was pending for more than one hour")
        val rejected = findOrder(old.id)!!
        assertThat(rejected.orderStatus).isEqualTo(OrderStatus.REJECTED)
        assertThat(rejected.rejectionReason).isNull()
        assertThat(errorMessage(place(old.id, buyer(userId)))).endsWith("Order ${old.id} is not pending: it was already placed or rejected")
        // a placed order older than 1 h stays placed
        val oldPlaced = insertOrder(userId, createdAt = now().minusSeconds(7200), status = OrderStatus.PLACED)
        assertThat(errorMessage(place(oldPlaced.id, buyer(userId)))).endsWith("is not pending: it was already placed or rejected")
        assertThat(findOrder(oldPlaced.id)!!.orderStatus).isEqualTo(OrderStatus.PLACED)
        assertThat(dapr.published(OrderEvents.ORDER_CREATED)).hasSize(2)
    }

    @Test
    fun `concurrent placements of one order place and publish it once`() {
        val userId = registerUser()
        val order = insertOrder(userId)
        val pool = Executors.newFixedThreadPool(4)
        val results = try {
            (1..4).map { pool.submit<JsonNode> { place(order.id, buyer(userId)) } }.map { it.get() }
        } finally {
            pool.shutdown()
        }
        assertThat(results.count { it["errors"] == null }).isEqualTo(1)
        assertThat(results.filter { it["errors"] != null }.map { errorMessage(it) }).allMatch { it.endsWith("is not pending: it was already placed or rejected") }
        assertThat(dapr.published(OrderEvents.ORDER_CREATED).count { it.json["id"].asString() == order.id.toString() }).isEqualTo(1)
    }

    @Test
    fun `order and orderItem are visible to the owner and employees`() {
        val userId = UUID.randomUUID()
        val order = insertOrder(userId, items = 3)
        val query = """{ order(id: "${order.id}") { id orderStatus createdAt compensatableOrderAmount paymentInformationId
            orderItems(first: 2, skip: 0, orderBy: {direction: DESC}) { totalCount hasNextPage nodes { id discounts { totalCount nodes { id } } } } } }"""
        assertThat(errorMessage(graphql(query))).contains("Unauthorized access")
        val other = UUID.randomUUID()
        assertThat(errorMessage(graphql(query, user = buyer(other)))).contains("Unauthorized access: $other is not an employee or admin")
        val own = graphql(query, user = buyer(userId))
        assertThat(own["errors"]).isNull()
        val connection = own["data"]["order"]["orderItems"]
        assertThat(connection["totalCount"].asInt()).isEqualTo(3)
        assertThat(connection["hasNextPage"].asBoolean()).isTrue()
        assertThat(connection["nodes"].toList().map { UUID.fromString(it["id"].asString()) })
            .containsExactlyElementsOf(order.items.map { it.id!! }.sortedWith(UUID_ORDER.reversed()).take(2))
        assertThat(connection["nodes"][0]["discounts"]["totalCount"].asInt()).isEqualTo(1)
        assertThat(graphql(query, user = authorizedUserHeader(other, "employee"))["errors"]).isNull()
        assertThat(errorMessage(graphql("""{ order(id: "${UUID.randomUUID()}") { id } }""", user = employee())))
            .endsWith(": null")

        val itemId = order.items[1].id!!
        val item = graphql("""{ orderItem(id: "$itemId") { id count compensatableAmount discounts { totalCount } } }""", user = buyer(userId))
        assertThat(item["errors"]).isNull()
        assertThat(item["data"]["orderItem"]["compensatableAmount"].asInt()).isEqualTo(11)
        assertThat(errorMessage(graphql("""{ orderItem(id: "$itemId") { id } }""", user = buyer(other))))
            .contains("Unauthorized access: $other is not an employee or admin")
        assertThat(errorMessage(graphql("""{ orderItem(id: "${UUID.randomUUID()}") { id } }""", user = employee())))
            .endsWith(": null")
    }

    @Test
    fun `federation entities resolve without authorization`() {
        val userId = registerUser()
        val order = insertOrder(userId)
        val result = graphql(
            """
            query(${'$'}r: [_Any!]!) { _entities(representations: ${'$'}r) {
              ... on Order { id orderStatus user { id } }
              ... on User { id }
              ... on OrderItem { id count }
            } }
            """.trimIndent(),
            mapOf(
                "r" to listOf(
                    mapOf("__typename" to "Order", "id" to order.id.toString()),
                    mapOf("__typename" to "User", "id" to userId.toString()),
                    mapOf("__typename" to "OrderItem", "id" to order.items[0].id.toString()),
                )
            )
        )
        assertThat(result["errors"]).isNull()
        val entities = result["data"]["_entities"]
        assertThat(entities[0]["user"]["id"].asString()).isEqualTo(userId.toString())
        assertThat(entities[1]["id"].asString()).isEqualTo(userId.toString())
        assertThat(entities[2]["id"].asString()).isEqualTo(order.items[0].id.toString())
        // an unknown order fails only its own entity
        val unknown = graphql(
            """query(${'$'}r: [_Any!]!) { _entities(representations: ${'$'}r) { ... on Order { id } } }""",
            mapOf("r" to listOf(mapOf("__typename" to "Order", "id" to UUID.randomUUID().toString()), mapOf("__typename" to "Order", "id" to order.id.toString())))
        )
        assertThat(unknown["errors"]).hasSize(1)
        assertThat(unknown["data"]["_entities"][1]["id"].asString()).isEqualTo(order.id.toString())
    }

    @Test
    fun `User orders pages by first and skip with a default of 25`() {
        val userId = registerUser()
        val base = OffsetDateTime.parse("2026-01-01T00:00:00Z")
        val orders = (0 until 27).map { insertOrder(userId, createdAt = base.plusSeconds((it % 5).toLong())) }
        insertOrder(UUID.randomUUID()) // another user's order
        fun page(args: String, user: String? = buyer(userId)): JsonNode = graphql(
            """query(${'$'}r: [_Any!]!) { _entities(representations: ${'$'}r) { ... on User { orders$args { totalCount hasNextPage nodes { id } } } } }""",
            mapOf("r" to listOf(mapOf("__typename" to "User", "id" to userId.toString()))), user
        )
        fun ids(result: JsonNode): List<UUID> = result["data"]["_entities"][0]["orders"]["nodes"].toList().map { UUID.fromString(it["id"].asString()) }
        fun hasNextPage(result: JsonNode) = result["data"]["_entities"][0]["orders"]["hasNextPage"].asBoolean()
        val byIdAsc = orders.map { it.id }.sortedWith(UUID_ORDER)

        val first = page("")
        assertThat(first["errors"]).isNull()
        assertThat(first["data"]["_entities"][0]["orders"]["totalCount"].asInt()).isEqualTo(27)
        assertThat(hasNextPage(first)).isTrue()
        assertThat(ids(first)).containsExactlyElementsOf(byIdAsc.take(25))
        val p = page("(first: 5, skip: 5)")
        assertThat(ids(p)).containsExactlyElementsOf(byIdAsc.subList(5, 10))
        assertThat(hasNextPage(p)).isTrue()
        assertThat(hasNextPage(page("(first: 5, skip: 21)"))).isTrue()
        val lastPage = page("(first: 5, skip: 22)")
        assertThat(ids(lastPage)).containsExactlyElementsOf(byIdAsc.subList(22, 27))
        assertThat(hasNextPage(lastPage)).isFalse()
        assertThat(ids(page("(first: 0)"))).isEmpty()
        // ordering: other fields than the id break ties by descending id
        assertThat(ids(page("(first: 30, orderBy: {direction: DESC})"))).containsExactlyElementsOf(byIdAsc.reversed())
        val byCreatedAt = orders.map { it.order }
            .sortedWith(compareBy<org.misarch.order.persistence.model.OrderEntity> { it.createdAt }.thenBy(UUID_ORDER.reversed()) { it.id!! })
            .map { it.id }
        assertThat(ids(page("(first: 30, orderBy: {field: CREATED_AT})"))).containsExactlyElementsOf(byCreatedAt)
        for (field in listOf("NAME", "LAST_UPDATED_AT", "USER_ID")) {
            assertThat(ids(page("(first: 30, orderBy: {field: $field, direction: ASC})"))).containsExactlyElementsOf(byIdAsc.reversed())
        }
        // authorization and arguments
        val other = UUID.randomUUID()
        assertThat(errorMessage(page("", buyer(other)))).contains("Unauthorized access: $other is not an employee or admin")
        assertThat(page("", authorizedUserHeader(other, "employee"))["errors"]).isNull()
        assertThat(page("(first: -1)")["errors"].toList().map { it["message"].asString() }).anyMatch { it.endsWith("Limit must not be negative.") }
        assertThat(errorMessage(page("(skip: -1)"))).endsWith("Offset must not be negative.")
        // a user without orders
        val lonely = UUID.randomUUID()
        val none = graphql(
            """query(${'$'}r: [_Any!]!) { _entities(representations: ${'$'}r) { ... on User { orders { totalCount hasNextPage nodes { id } } } } }""",
            mapOf("r" to listOf(mapOf("__typename" to "User", "id" to lonely.toString()))), buyer(lonely)
        )
        assertThat(none["data"]["_entities"][0]["orders"].toString()).isEqualTo("""{"totalCount":0,"hasNextPage":false,"nodes":[]}""")
        // Order.user.orders
        val nested = graphql("""{ order(id: "${orders[0].id}") { user { orders(first: 1) { totalCount } } } }""", user = buyer(userId))
        assertThat(nested["data"]["order"]["user"]["orders"]["totalCount"].asInt()).isEqualTo(27)
    }

}
