package org.misarch.order.event

import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.misarch.common.event.DaprConfiguration
import org.misarch.common.event.DaprProperties
import org.misarch.order.persistence.model.OrderCompensationRecord
import org.misarch.order.persistence.model.OrderRecord
import org.misarch.order.rust.RustError
import org.misarch.order.rust.RustMessages
import org.misarch.common.rust.RustDateTime
import org.misarch.order.rust.U64
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import tools.jackson.databind.json.JsonMapper

/**
 * Publishes the order service's events like the original (`reqwest` POST to
 * `<dapr>/v1.0/publish/pubsub/<topic>`, serde JSON body): only a transport failure is an error, the HTTP status of
 * the sidecar's answer is ignored (unlike the shared `EventPublisher`, which throws on non-2xx).
 */
@Component
class OrderEventPublisher(
    @Qualifier(DaprConfiguration.DAPR_WEB_CLIENT) private val webClient: WebClient,
    private val daprProperties: DaprProperties,
) {

    /** `order/order/created` with the `OrderDTO` (order_dto.rs) of a placed order. */
    suspend fun publishOrderCreated(order: OrderRecord, cvc: Int?) {
        publish(OrderEvents.ORDER_CREATED, orderDto(order, cvc))
    }

    /** `order/order-compensation/created` with the documented `OrderCompensationDTO`. */
    suspend fun publishOrderCompensationCreated(compensation: OrderCompensationRecord) {
        publish(OrderEvents.ORDER_COMPENSATION_CREATED, compensationDto(compensation))
    }

    private suspend fun publish(topic: String, payload: Map<String, Any?>) {
        val url = "${daprProperties.httpEndpoint.trimEnd('/')}/v1.0/publish/${daprProperties.pubsubName}/$topic"
        try {
            webClient.post()
                .uri(java.net.URI.create(url))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(MAPPER.writeValueAsBytes(payload))
                .exchangeToMono { response -> response.releaseBody() }
                .awaitSingleOrNull()
        } catch (e: Exception) {
            throw RustError(RustMessages.errorSendingRequest(url))
        }
    }

    companion object {
        private val MAPPER = JsonMapper.builder().build()

        /**
         * serde JSON of `OrderDTO` (`rename_all = "camelCase"`, `None` → `null`, timestamps chrono RFC 3339 with `Z`).
         * FIXED (docs/fixes/order.md): `paymentAuthorization` is the documented `{"cvc": n}` (the original's serde
         * enum produced `{"cVC": n}`, which discount read as 0).
         */
        fun orderDto(order: OrderRecord, cvc: Int?): Map<String, Any?> = linkedMapOf(
            "id" to order.id.toString(),
            "userId" to order.userId.toString(),
            "createdAt" to RustDateTime.toSerde(order.createdAt),
            "orderStatus" to order.orderStatus.name,
            "placedAt" to RustDateTime.toSerde(
                order.placedAt ?: throw RustError("OrderDTO cannot be created, `placed_at` of the given Order is `None`")
            ),
            "rejectionReason" to order.rejectionReason?.name,
            "orderItems" to order.items.map { item ->
                linkedMapOf(
                    "id" to item.id.toString(),
                    "createdAt" to RustDateTime.toSerde(item.createdAt),
                    "productVariantId" to item.productVariantId.toString(),
                    "productVariantVersionId" to item.productVariantVersion.id.toString(),
                    "taxRateVersionId" to item.taxRateVersion.id.toString(),
                    "shoppingCartItemId" to item.shoppingCartItemId.toString(),
                    "count" to U64.json(item.count),
                    "compensatableAmount" to U64.json(item.compensatableAmount),
                    "shipmentMethodId" to item.shipmentMethodId.toString(),
                    "discountIds" to item.discounts.map { it.id.toString() },
                )
            },
            "shipmentAddressId" to order.shipmentAddressId.toString(),
            "invoiceAddressId" to order.invoiceAddressId.toString(),
            "compensatableOrderAmount" to U64.json(order.compensatableOrderAmount),
            "paymentInformationId" to order.paymentInformationId.toString(),
            "paymentAuthorization" to cvc?.let { linkedMapOf("cvc" to it) },
            "vatNumber" to order.vatNumber,
        )

        /**
         * The documented `order/order-compensation/created` payload (FIXED: the original sent only
         * `{"id", "amount_to_compensate"}`; the topic has no subscriber in MiSArch).
         */
        fun compensationDto(compensation: OrderCompensationRecord): Map<String, Any?> = linkedMapOf(
            "id" to compensation.id.toString(),
            "orderId" to compensation.orderId.toString(),
            "orderItemIds" to compensation.orderItemIds.map { it.toString() },
            "triggeredAt" to RustDateTime.toSerde(compensation.triggeredAt),
            "amountToCompensate" to U64.json(compensation.amountToCompensate),
        )

        fun toJson(payload: Map<String, Any?>): String = MAPPER.writeValueAsString(payload)
    }
}
