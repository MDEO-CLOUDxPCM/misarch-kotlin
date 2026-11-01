package org.misarch.order

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.misarch.common.event.EventJson
import org.misarch.order.graphql.model.OrderStatus
import org.misarch.order.persistence.model.OrderCompensationEntity
import org.misarch.order.persistence.model.OrderEntity
import org.misarch.order.persistence.model.OrderItemEntity
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import java.io.File
import java.time.OffsetDateTime
import java.util.UUID

/**
 * The published event payloads (`EventJson`): field order, nulls, timestamps.
 */
class EventPayloadTest {

    private fun uuid(n: Int) = UUID.fromString("00000000-0000-0000-0000-%012d".format(n))

    private fun order(createdAt: String, placedAt: String?, vatNumber: String?) = OrderEntity(
        userId = uuid(2), createdAt = OffsetDateTime.parse(createdAt), orderStatus = OrderStatus.PLACED,
        placedAt = placedAt?.let { OffsetDateTime.parse(it) }, rejectionReason = null, shipmentAddressId = uuid(3),
        invoiceAddressId = uuid(4), compensatableOrderAmount = 20, paymentInformationId = uuid(5), vatNumber = vatNumber,
        id = uuid(1),
    )

    private fun item(id: UUID, createdAt: String) = OrderItemEntity(
        orderId = uuid(1), position = 0, createdAt = OffsetDateTime.parse(createdAt), productVariantId = uuid(12),
        productVariantIsPubliclyVisible = true, productVariantVersionId = uuid(13), productVariantVersionPrice = 20,
        productVariantVersionTaxRateId = uuid(14), taxRateVersionId = uuid(15), taxRateVersionRate = 0.19,
        taxRateVersionVersion = 1, shoppingCartItemId = uuid(16), count = 2, compensatableAmount = 20,
        shipmentMethodId = uuid(17), id = id,
    )

    @Test
    fun `the order created payload writes nulls and millisecond timestamps with Z`() {
        val items = listOf(item(uuid(11), "2026-10-13T12:00:00.120Z").toEventDTO(listOf(uuid(18), uuid(19))))
        val order = order("2026-10-13T12:00:00.120Z", "2026-10-13T12:00:05Z", null)
        assertThat(EventJson.toJson(order.toEventDTO(items, 123))).isEqualTo(
            """{"id":"${uuid(1)}","userId":"${uuid(2)}","createdAt":"2026-10-13T12:00:00.120Z","orderStatus":"PLACED",""" +
                """"placedAt":"2026-10-13T12:00:05Z","rejectionReason":null,"orderItems":[{"id":"${uuid(11)}",""" +
                """"createdAt":"2026-10-13T12:00:00.120Z","productVariantId":"${uuid(12)}","productVariantVersionId":"${uuid(13)}",""" +
                """"taxRateVersionId":"${uuid(15)}","shoppingCartItemId":"${uuid(16)}","count":2,"compensatableAmount":20,""" +
                """"shipmentMethodId":"${uuid(17)}","discountIds":["${uuid(18)}","${uuid(19)}"]}],""" +
                """"shipmentAddressId":"${uuid(3)}","invoiceAddressId":"${uuid(4)}","compensatableOrderAmount":20,""" +
                """"paymentInformationId":"${uuid(5)}","paymentAuthorization":{"cvc":123},"vatNumber":null}"""
        )
        assertThat(EventJson.toJson(order("2026-10-13T12:00:00.120Z", "2026-10-13T12:00:05Z", "AB1234").toEventDTO(items, null)))
            .contains(""""paymentAuthorization":null,"vatNumber":"AB1234"""")
    }

    @Test
    fun `the order created payload equals the event captured from the running original`() {
        val mapper = JsonMapper.builder().build()
        val captured = mapper.readTree(File(System.getProperty("misarch.reference.dir"), "events/order_order_created.json"))["data"]
        val capturedItem = captured["orderItems"][0]
        fun id(name: String, node: tools.jackson.databind.JsonNode = captured) = UUID.fromString(node[name].asString())
        val order = OrderEntity(
            userId = id("userId"), createdAt = OffsetDateTime.parse(captured["createdAt"].asString()),
            orderStatus = OrderStatus.PLACED, placedAt = OffsetDateTime.parse(captured["placedAt"].asString()),
            rejectionReason = null, shipmentAddressId = id("shipmentAddressId"), invoiceAddressId = id("invoiceAddressId"),
            compensatableOrderAmount = 23, paymentInformationId = id("paymentInformationId"), vatNumber = "AB1234", id = id("id"),
        )
        val item = OrderItemEntity(
            orderId = order.id!!, position = 0, createdAt = OffsetDateTime.parse(capturedItem["createdAt"].asString()),
            productVariantId = id("productVariantId", capturedItem), productVariantIsPubliclyVisible = true,
            productVariantVersionId = id("productVariantVersionId", capturedItem), productVariantVersionPrice = 23,
            productVariantVersionTaxRateId = UUID.randomUUID(), taxRateVersionId = id("taxRateVersionId", capturedItem),
            taxRateVersionRate = 0.19, taxRateVersionVersion = 1, shoppingCartItemId = id("shoppingCartItemId", capturedItem),
            count = 1, compensatableAmount = 23, shipmentMethodId = id("shipmentMethodId", capturedItem), id = id("id", capturedItem),
        )
        val ours = mapper.readTree(EventJson.toJson(order.toEventDTO(listOf(item.toEventDTO(emptyList())), 123)))
        // FIXED (docs/fixes/order.md): paymentAuthorization is {"cvc":123} (the original sent {"cVC":123})
        val expected = captured.deepCopy() as ObjectNode
        expected.set("paymentAuthorization", mapper.readTree("""{"cvc":123}"""))
        assertThat(ours).isEqualTo(expected)
    }

    @Test
    fun `the order compensation payload`() {
        val compensation = OrderCompensationEntity(uuid(2), OffsetDateTime.parse("2026-10-13T12:00:00.120Z"), 42, uuid(1))
        assertThat(EventJson.toJson(compensation.toEventDTO(listOf(uuid(3))))).isEqualTo(
            """{"id":"${uuid(1)}","orderId":"${uuid(2)}","orderItemIds":["${uuid(3)}"],"triggeredAt":"2026-10-13T12:00:00.120Z","amountToCompensate":42}"""
        )
    }

}
