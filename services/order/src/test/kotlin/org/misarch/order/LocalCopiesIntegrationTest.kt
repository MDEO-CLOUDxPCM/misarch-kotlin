package org.misarch.order

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.misarch.order.event.OrderEvents
import org.misarch.order.mechanisms.LocalCopies
import org.misarch.order.mechanisms.LocalCopiesEventController
import org.springframework.test.context.TestPropertySource
import tools.jackson.databind.JsonNode
import java.util.UUID

/**
 * Local copies switched on (docs/MECHANISMS.md): the copies are fed by events, createOrder reads them instead of
 * calling discount and shipment, and falls back to the remote call when a copy cannot answer exactly.
 * With the switch off (all other order tests) createOrder makes its 4 invocations as before.
 */
@TestPropertySource(properties = ["${LocalCopies.PROPERTY}=true"])
class LocalCopiesIntegrationTest : OrderTestSupport() {

    private val past = "2020-01-01T00:00:00Z"
    private val future = "2099-01-01T00:00:00Z"

    /** Two variants (prices 20, 30) in two products; pv1 has a variant discount 0.5, pv2 a category discount 0.1. */
    inner class Setup {
        val userId = registerUser()
        val addressId = registerUserAddress(userId)
        val tax = registerTaxRate(0.19)
        val p1: UUID = UUID.randomUUID()
        val p2: UUID = UUID.randomUUID()
        val category: UUID = UUID.randomUUID()
        val pv1: UUID = UUID.randomUUID()
        val pv2: UUID = UUID.randomUUID()
        val pvv1 = registerProductVariantVersion(pv1, 20, tax.first)   // weight 0.5
        val pvv2 = registerProductVariantVersion(pv2, 30, tax.first)
        val methodId: UUID = UUID.randomUUID()
        val discount1: UUID = UUID.randomUUID()
        val discount2: UUID = UUID.randomUUID()
        val cart1: UUID = UUID.randomUUID()
        val cart2: UUID = UUID.randomUUID()
        val coupon: UUID = UUID.randomUUID()

        init {
            deliverOk("catalog/product/created", """{"id":"$p1","categoryIds":[]}""")
            deliverOk("catalog/product/created", """{"id":"$p2","categoryIds":["$category"]}""")
            deliverOk("catalog/product-variant/created", """{"id":"$pv1","productId":"$p1"}""")
            deliverOk("catalog/product-variant/created", """{"id":"$pv2","productId":"$p2"}""")
            discount(discount1, 0.5, variants = listOf(pv1))
            discount(discount2, 0.1, categories = listOf(category))
            // an expired and a coupon-bound discount on pv1 never apply
            discount(UUID.randomUUID(), 0.9, variants = listOf(pv1), until = past)
            val couponDiscount = UUID.randomUUID()
            discount(couponDiscount, 0.9, variants = listOf(pv1))
            deliverOk(OrderEvents.COUPON_CREATED, """{"id":"$coupon","code":"X","discountId":"$couponDiscount"}""")
            deliverOk(OrderEvents.SHIPMENT_METHOD_CREATED, """{"id":"$methodId","name":"m","baseFees":3,"feesPerItem":1,"feesPerKg":2}""")
        }

        fun discount(id: UUID, value: Double, variants: List<UUID> = emptyList(), categories: List<UUID> = emptyList(), until: String = future) =
            deliverOk("discount/discount/created", """{"id":"$id","discount":$value,"validFrom":"$past","validUntil":"$until",
                "minOrderAmount":null,"discountAppliesToProductVariantIds":${ids(variants)},"discountAppliesToProductIds":[],
                "discountAppliesToCategoryIds":${ids(categories)}}""")

        fun stubs(discounts: Map<UUID, List<Pair<UUID, Double>>> = mapOf(pv1 to listOf(discount1 to 0.5), pv2 to emptyList())) =
            CheckoutStubs(mapOf(cart1 to (pv1 to 2L), cart2 to (pv2 to 1L)), mapOf(pv1 to 100L, pv2 to 100L), discounts, fees = 7)
                .install(dapr)

        fun create(coupons: List<UUID> = emptyList()): JsonNode = graphql(
            """mutation(${'$'}input: CreateOrderInput!) { createOrder(input: ${'$'}input) {
                 id compensatableOrderAmount orderItems { nodes { productVariant { id } compensatableAmount discounts { nodes { id } } } } } }""",
            mapOf("input" to mapOf(
                "userId" to userId.toString(), "shipmentAddressId" to addressId.toString(), "invoiceAddressId" to addressId.toString(),
                "paymentInformationId" to UUID.randomUUID().toString(),
                "orderItemInputs" to listOf(
                    mapOf("shoppingCartItemId" to cart1.toString(), "shipmentMethodId" to methodId.toString(), "couponIds" to coupons.map { it.toString() }),
                    mapOf("shoppingCartItemId" to cart2.toString(), "shipmentMethodId" to methodId.toString(), "couponIds" to emptyList<String>()),
                ),
            )),
            buyer(userId),
        )
    }

    private fun ids(list: List<UUID>) = list.joinToString(",", "[", "]") { "\"$it\"" }

    private fun JsonNode.ids(): List<String> = (0 until size()).map { this[it]["id"].asString() }

    private fun JsonNode.items(): Map<String, JsonNode> =
        this["data"]["createOrder"]["orderItems"]["nodes"].let { n -> (0 until n.size()).associateBy({ n[it]["productVariant"]["id"].asString() }, { n[it] }) }

    @Test
    fun `dapr subscribe adds the copies' topics`() {
        val body = webTestClient.get().uri("/dapr/subscribe").exchange().expectStatus().isOk
            .expectBody(String::class.java).returnResult().responseBody!!
        val subscriptions = mapper.readTree(body).associate { it["topic"].asString() to it["route"].asString() }
        LocalCopiesEventController.TOPICS.forEach {
            assertThat(subscriptions).containsEntry(it, "/subscription/$it")
        }
        assertThat(subscriptions).containsEntry(OrderEvents.SHIPMENT_METHOD_CREATED, "/subscription/${OrderEvents.SHIPMENT_METHOD_CREATED}")
    }

    @Test
    fun `createOrder reads discount and shipment method from the copies, no discount and shipment calls`() {
        val s = Setup()
        s.stubs()
        val result = s.create()
        assertThat(result["errors"]).isNull()
        assertThat(dapr.invocations.map { it.appId }).containsExactly("shoppingcart", "inventory")
        // pv1: 20 x 2 x 0.5 = 20; pv2: 30 x 0.9 = 27; fees 3 + 3 x 1 + (3 x 0.5 x 2) = 9
        assertThat(result["data"]["createOrder"]["compensatableOrderAmount"].asInt()).isEqualTo(56)
        val items = result.items()
        assertThat(items.getValue(s.pv1.toString())["discounts"]["nodes"].ids()).containsExactly(s.discount1.toString())
        assertThat(items.getValue(s.pv2.toString())["discounts"]["nodes"].ids()).containsExactly(s.discount2.toString())
    }

    @Test
    fun `fallback to the remote calls - coupons given, archived method, unknown variant`() {
        val s = Setup()
        s.stubs()
        // coupons: the discount call is made, shipment still from the copy
        assertThat(s.create(listOf(s.coupon))["errors"]).isNull()
        assertThat(dapr.invocations.map { it.appId }).containsExactly("shoppingcart", "inventory", "discount")

        // archived method: the shipment call is made (fees of the stub: 7)
        dapr.reset(); s.stubs()
        deliverOk("shipment/shipment-method/archived", """{"id":"${s.methodId}"}""")
        val archived = s.create()
        assertThat(archived["errors"]).isNull()
        assertThat(dapr.invocations.map { it.appId }).containsExactly("shoppingcart", "inventory", "shipment")
        assertThat(archived["data"]["createOrder"]["compensatableOrderAmount"].asInt()).isEqualTo(20 + 27 + 7)

        // a variant without a copy (no product-variant/created): the discount call is made
        val other = Setup()
        val unknownVariant = UUID.randomUUID()
        registerProductVariantVersion(unknownVariant, 10, other.tax.first)
        dapr.reset()
        CheckoutStubs(mapOf(other.cart1 to (unknownVariant to 1L)), mapOf(unknownVariant to 100L),
            mapOf(unknownVariant to emptyList()), fees = 7).install(dapr)
        val r = graphql(
            """mutation(${'$'}input: CreateOrderInput!) { createOrder(input: ${'$'}input) { compensatableOrderAmount } }""",
            mapOf("input" to mapOf(
                "userId" to other.userId.toString(), "shipmentAddressId" to other.addressId.toString(),
                "invoiceAddressId" to other.addressId.toString(), "paymentInformationId" to UUID.randomUUID().toString(),
                "orderItemInputs" to listOf(mapOf("shoppingCartItemId" to other.cart1.toString(),
                    "shipmentMethodId" to other.methodId.toString(), "couponIds" to emptyList<String>())),
            )),
            buyer(other.userId),
        )
        assertThat(r["errors"]).isNull()
        // the variant is unknown to the copy -> discount call; its version weight is known -> fees from the copy
        assertThat(dapr.invocations.map { it.appId }).containsExactly("shoppingcart", "inventory", "discount")
        // 10 + fees 3 + 1 + (0.5 x 2 = 1)
        assertThat(r["data"]["createOrder"]["compensatableOrderAmount"].asInt()).isEqualTo(15)
    }

    @Test
    fun `copies are upserts - an updated discount and product replace the copy`() {
        val s = Setup()
        s.stubs()
        // pv2's product loses the category, pv1's discount becomes 0.25
        deliverOk("catalog/product/updated", """{"id":"${s.p2}","categoryIds":[]}""")
        deliverOk("discount/discount/updated", """{"id":"${s.discount1}","discount":0.25,"validFrom":"$past",
            "validUntil":"$future","minOrderAmount":null,"discountAppliesToProductVariantIds":["${s.pv1}"],
            "discountAppliesToProductIds":[],"discountAppliesToCategoryIds":[]}""")
        val result = s.create()
        assertThat(result["errors"]).isNull()
        // pv1: 20 x 2 x 0.75 = 30; pv2: 30; fees 9
        assertThat(result["data"]["createOrder"]["compensatableOrderAmount"].asInt()).isEqualTo(69)
        assertThat(result.items().getValue(s.pv2.toString())["discounts"]["nodes"].ids()).isEmpty()
        assertThat(dapr.invocations.map { it.appId }).containsExactly("shoppingcart", "inventory")
    }

    companion object {
        /**
         * The Dapr SDK keeps the subscriptions of every `@Topic` bean in one static registry (`DaprRuntime`), so the
         * copies' topics of this test's context would otherwise show up in the `/dapr/subscribe` of the switched-off
         * contexts of the other test classes in this JVM.
         */
        @JvmStatic
        @AfterAll
        fun forgetCopyTopics() {
            val runtimeClass = Class.forName("io.dapr.springboot.DaprRuntime")
            val runtime = runtimeClass.getMethod("getInstance").also { it.isAccessible = true }.invoke(null)
            val builders = runtimeClass.getDeclaredField("subscriptionBuilders").also { it.isAccessible = true }
                .get(runtime) as MutableMap<*, *>
            val topicOf = Class.forName("io.dapr.springboot.DaprTopicKey").getMethod("getTopic").also { it.isAccessible = true }
            synchronized(runtime) {
                builders.keys.removeIf { topicOf.invoke(it) in LocalCopiesEventController.TOPICS }
            }
        }
    }
}
