package org.misarch.order

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.misarch.order.event.OrderEventPublisher
import org.misarch.order.graphql.model.OrderStatus
import org.misarch.order.persistence.model.DiscountRecord
import org.misarch.order.persistence.model.OrderCompensationRecord
import org.misarch.order.persistence.model.OrderItemRecord
import org.misarch.order.persistence.model.OrderRecord
import org.misarch.order.persistence.model.ProductVariantVersionRecord
import org.misarch.order.persistence.model.TaxRateVersionRecord
import org.misarch.order.rust.RustTypeNames
import org.misarch.order.service.NormalizedOrderItemInput
import org.misarch.order.service.checkAvailability
import org.misarch.order.service.compensatableAmount
import org.misarch.order.service.discountSet
import org.misarch.order.service.mapInputsToProductVariants
import org.misarch.order.service.normalizeOrderItemInputs
import org.misarch.order.service.orderAmount
import org.misarch.order.service.validateAllPresent
import java.time.Instant
import java.util.UUID

/**
 * Unit tests of the pure createOrder logic and the event DTOs.
 */
class CreateOrderLogicTest {

    private fun uuid(n: Int) = UUID.fromString("00000000-0000-0000-0000-%012d".format(n))

    @Test
    fun `order item inputs behave like a Rust BTreeSet`() {
        val a1 = NormalizedOrderItemInput(uuid(2), uuid(100), setOf(uuid(7), uuid(8)))
        val a1Again = NormalizedOrderItemInput(uuid(2), uuid(100), setOf(uuid(8), uuid(7)))
        val a2 = NormalizedOrderItemInput(uuid(2), uuid(101), emptySet())
        val b = NormalizedOrderItemInput(uuid(1), uuid(100), emptySet())
        // sorted by cart item id, adjacent full duplicates collapse, same cart item with other fields kept
        assertThat(normalizeOrderItemInputs(listOf(a1, a1Again, b))).containsExactly(b, a1Again)
        assertThat(normalizeOrderItemInputs(listOf(a1, a2, b))).containsExactly(b, a1, a2)
        // only adjacent duplicates collapse (DedupSortedIter)
        assertThat(normalizeOrderItemInputs(listOf(a1, a2, a1Again))).containsExactly(a1, a2, a1Again)
    }

    @Test
    fun `cart mapping fails for the first missing cart item and the last input wins per variant`() {
        val pv = uuid(50)
        val inputs = listOf(
            NormalizedOrderItemInput(uuid(1), uuid(100), emptySet()),
            NormalizedOrderItemInput(uuid(2), uuid(101), emptySet()),
        )
        val (counts, byVariant) = mapInputsToProductVariants(inputs, mapOf(uuid(1) to (pv to 3L), uuid(2) to (pv to 4L)))
        assertThat(counts).containsExactlyEntriesOf(mapOf(pv to 4L))
        assertThat(byVariant[pv]!!.shipmentMethodId).isEqualTo(uuid(101))
        assertThatThrownBy { mapInputsToProductVariants(inputs, mapOf(uuid(1) to (pv to 3L))) }.hasMessage(
            "`(bson::uuid::Uuid, u64)` for product variant of UUID: `${uuid(2)}` is not present in " +
                "`std::collections::hash::map::HashMap<bson::uuid::Uuid, (bson::uuid::Uuid, u64)>`. "
        )
    }

    @Test
    fun `validate objects reports the last missing id`() {
        validateAllPresent(listOf(uuid(1), uuid(1)), setOf(uuid(1)), RustTypeNames.COUPON)
        assertThatThrownBy { validateAllPresent(listOf(uuid(1), uuid(2), uuid(3), uuid(4)), setOf(uuid(1), uuid(4)), RustTypeNames.COUPON) }
            .hasMessage("misarch_order::graphql::model::foreign_types::Coupon with UUID: `${uuid(3)}` is not present in the system.")
    }

    @Test
    fun `availability check`() {
        checkAvailability(mapOf(uuid(1) to 5L, uuid(2) to 1L), mapOf(uuid(1) to 5L, uuid(2) to 1L))
        assertThatThrownBy { checkAvailability(mapOf(uuid(1) to 4L), mapOf(uuid(1) to 5L)) }
            .hasMessage("Not all requested product variants are available.")
        assertThatThrownBy { checkAvailability(mapOf(uuid(1) to 4L), mapOf(uuid(2) to 1L)) }.hasMessage(
            "`u64` for product variant of UUID: `${uuid(2)}` is not present in `std::collections::hash::map::HashMap<bson::uuid::Uuid, u64>`. "
        )
        // a negative cart count is a huge u64 -> never available
        assertThatThrownBy { checkAvailability(mapOf(uuid(1) to 100L), mapOf(uuid(1) to -1L)) }
            .hasMessage("Not all requested product variants are available.")
    }

    @Test
    fun `amounts are price x count x (1 - discount), rounded (fixed)`() {
        // FIXED: the original multiplied by the discount fraction itself, ignored the count and truncated
        assertThat(compensatableAmount(20, 1, listOf(DiscountRecord(uuid(1), 0.2)))).isEqualTo(16)
        assertThat(compensatableAmount(20, 3, emptyList())).isEqualTo(60)
        assertThat(compensatableAmount(99, 1, listOf(DiscountRecord(uuid(2), 0.5), DiscountRecord(uuid(1), 0.5)))).isEqualTo(25) // 24.75
        assertThat(compensatableAmount(10, 1, listOf(DiscountRecord(uuid(1), 0.7)))).isEqualTo(3) // 2.9999999999999996
        assertThat(compensatableAmount(10, 2, listOf(DiscountRecord(uuid(1), 1.0)))).isEqualTo(0)
        assertThat(orderAmount(listOf(20L to 1L, 30L to 2L))).isEqualTo(80)
        assertThat(orderAmount(listOf(0xFFFF_FFFFL to 1L, 2L to 1L))).isEqualTo(Int.MAX_VALUE.toLong()) // saturates (GraphQL Int)
        assertThat(discountSet(listOf(DiscountRecord(uuid(2), 0.1), DiscountRecord(uuid(1), 0.3), DiscountRecord(uuid(2), 0.2))))
            .containsExactly(DiscountRecord(uuid(1), 0.3), DiscountRecord(uuid(2), 0.2))
    }

    @Test
    fun `order created DTO is the serde JSON of the original`() {
        val item = OrderItemRecord(
            id = uuid(11), createdAt = Instant.parse("2026-10-13T12:00:00.120Z"), productVariantId = uuid(12),
            productVariantIsPubliclyVisible = true, productVariantVersion = ProductVariantVersionRecord(uuid(13), 20, uuid(14)),
            taxRateVersion = TaxRateVersionRecord(uuid(15), 0.19, 1), shoppingCartItemId = uuid(16), count = 2,
            compensatableAmount = 20, shipmentMethodId = uuid(17),
            discounts = listOf(DiscountRecord(uuid(18), 1.0), DiscountRecord(uuid(19), 1.0)),
        )
        val order = OrderRecord(
            id = uuid(1), userId = uuid(2), createdAt = Instant.parse("2026-10-13T12:00:00.120Z"),
            orderStatus = OrderStatus.PLACED, placedAt = Instant.parse("2026-10-13T12:00:05Z"), rejectionReason = null,
            items = listOf(item), shipmentAddressId = uuid(3), invoiceAddressId = uuid(4), compensatableOrderAmount = 20,
            paymentInformationId = uuid(5), vatNumber = null,
        )
        assertThat(OrderEventPublisher.toJson(OrderEventPublisher.orderDto(order, 123))).isEqualTo(
            """{"id":"${uuid(1)}","userId":"${uuid(2)}","createdAt":"2026-10-13T12:00:00.120Z","orderStatus":"PLACED",""" +
                """"placedAt":"2026-10-13T12:00:05Z","rejectionReason":null,"orderItems":[{"id":"${uuid(11)}",""" +
                """"createdAt":"2026-10-13T12:00:00.120Z","productVariantId":"${uuid(12)}","productVariantVersionId":"${uuid(13)}",""" +
                """"taxRateVersionId":"${uuid(15)}","shoppingCartItemId":"${uuid(16)}","count":2,"compensatableAmount":20,""" +
                """"shipmentMethodId":"${uuid(17)}","discountIds":["${uuid(18)}","${uuid(19)}"]}],""" +
                """"shipmentAddressId":"${uuid(3)}","invoiceAddressId":"${uuid(4)}","compensatableOrderAmount":20,""" +
                """"paymentInformationId":"${uuid(5)}","paymentAuthorization":{"cvc":123},"vatNumber":null}"""
        )
        val withoutCvc = OrderEventPublisher.toJson(OrderEventPublisher.orderDto(order.copy(vatNumber = "AB1234"), null))
        assertThat(withoutCvc).contains(""""paymentAuthorization":null,"vatNumber":"AB1234"""")
        assertThatThrownBy { OrderEventPublisher.orderDto(order.copy(placedAt = null), null) }
            .hasMessage("OrderDTO cannot be created, `placed_at` of the given Order is `None`")
    }

    @Test
    fun `order created DTO equals the event captured from the running original`() {
        val mapper = tools.jackson.databind.json.JsonMapper.builder().build()
        val captured = mapper.readTree(java.io.File("../../reference/events/order_order_created.json"))["data"]
        val item = captured["orderItems"][0]
        fun id(node: tools.jackson.databind.JsonNode, name: String) = UUID.fromString(node[name].asString())
        val order = OrderRecord(
            id = id(captured, "id"), userId = id(captured, "userId"), createdAt = Instant.parse(captured["createdAt"].asString()),
            orderStatus = OrderStatus.PLACED, placedAt = Instant.parse(captured["placedAt"].asString()), rejectionReason = null,
            items = listOf(
                OrderItemRecord(
                    id = id(item, "id"), createdAt = Instant.parse(item["createdAt"].asString()), productVariantId = id(item, "productVariantId"),
                    productVariantIsPubliclyVisible = true,
                    productVariantVersion = ProductVariantVersionRecord(id(item, "productVariantVersionId"), 23, UUID.randomUUID()),
                    taxRateVersion = TaxRateVersionRecord(id(item, "taxRateVersionId"), 0.19, 1),
                    shoppingCartItemId = id(item, "shoppingCartItemId"), count = 1, compensatableAmount = 23,
                    shipmentMethodId = id(item, "shipmentMethodId"), discounts = emptyList(),
                )
            ),
            shipmentAddressId = id(captured, "shipmentAddressId"), invoiceAddressId = id(captured, "invoiceAddressId"),
            compensatableOrderAmount = 23, paymentInformationId = id(captured, "paymentInformationId"), vatNumber = "AB1234",
        )
        val ours = mapper.readTree(OrderEventPublisher.toJson(OrderEventPublisher.orderDto(order, 123)))
        // FIXED: paymentAuthorization is {"cvc":123} (the original sent {"cVC":123}, which discount read as 0)
        val expected = captured.deepCopy() as tools.jackson.databind.node.ObjectNode
        expected.set("paymentAuthorization", mapper.readTree("""{"cvc":123}"""))
        assertThat(ours).isEqualTo(expected)
    }

    @Test
    fun `order compensation DTO is the documented one (fixed)`() {
        val compensation = OrderCompensationRecord(uuid(1), uuid(2), listOf(uuid(3)), Instant.parse("2026-10-13T12:00:00.120Z"), 42)
        assertThat(OrderEventPublisher.toJson(OrderEventPublisher.compensationDto(compensation))).isEqualTo(
            """{"id":"${uuid(1)}","orderId":"${uuid(2)}","orderItemIds":["${uuid(3)}"],"triggeredAt":"2026-10-13T12:00:00.120Z","amountToCompensate":42}"""
        )
    }
}
