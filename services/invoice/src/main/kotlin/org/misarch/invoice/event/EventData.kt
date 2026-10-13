package org.misarch.invoice.event

import org.misarch.common.rust.AxumJson
import org.misarch.common.rust.AxumJson.DataError
import org.misarch.common.rust.AxumJson.optString
import org.misarch.common.rust.AxumJson.req
import org.misarch.common.rust.AxumJson.reqArray
import org.misarch.common.rust.AxumJson.reqDateTime
import org.misarch.common.rust.AxumJson.reqObject
import org.misarch.common.rust.AxumJson.reqString
import org.misarch.common.rust.AxumJson.reqUnsigned
import org.misarch.common.rust.AxumJson.reqUuid
import tools.jackson.databind.JsonNode
import java.math.BigInteger
import java.time.Instant
import java.util.UUID

/**
 * Event payloads of the original `event/http_event_service.rs`, decoded with serde's rules (docs/specs/invoice.md §5).
 * Every `fromJson` throws [DataError] (→ 422) where serde fails.
 */

/** `OrderEventData` (camelCase; enums with serde's default variant names). */
data class OrderEventData(
    val id: UUID,
    val userId: UUID,
    val createdAt: Instant,
    /** serde variant name: `Pending`, `Placed` or `Rejected`. */
    val orderStatus: String,
    val placedAt: Instant,
    /** serde variant name `InvalidOrderData` / `InventoryReservationFailed`, or null. */
    val rejectionReason: String?,
    val orderItems: List<OrderItemEventData>,
    val shipmentAddressId: UUID,
    val invoiceAddressId: UUID,
    val compensatableOrderAmount: BigInteger,
    val paymentInformationId: UUID,
    /** `PaymentAuthorizationEventData::CVC(u16)`, externally tagged `{"cVC": n}`; null if absent. */
    val paymentAuthorizationCvc: Int?,
    val vatNumber: String?,
) {
    companion object {
        private val ORDER_STATUS = setOf("Pending", "Placed", "Rejected")
        private val REJECTION_REASON = setOf("InvalidOrderData", "InventoryReservationFailed")

        fun fromJson(node: JsonNode) = OrderEventData(
            id = node.reqUuid("id"),
            userId = node.reqUuid("userId"),
            createdAt = node.reqDateTime("createdAt"),
            orderStatus = node.reqString("orderStatus").also {
                if (it !in ORDER_STATUS) throw DataError("unknown variant `$it`, expected one of `Pending`, `Placed`, `Rejected`")
            },
            placedAt = node.reqDateTime("placedAt"),
            rejectionReason = node.optString("rejectionReason")?.also {
                if (it !in REJECTION_REASON) throw DataError("unknown variant `$it`")
            },
            orderItems = node.reqArray("orderItems").map { item ->
                if (!item.isObject) throw DataError("invalid type: expected struct OrderItemEventData")
                OrderItemEventData.fromJson(item)
            },
            shipmentAddressId = node.reqUuid("shipmentAddressId"),
            invoiceAddressId = node.reqUuid("invoiceAddressId"),
            compensatableOrderAmount = node.reqUnsigned("compensatableOrderAmount"),
            paymentInformationId = node.reqUuid("paymentInformationId"),
            paymentAuthorizationCvc = paymentAuthorization(node),
            vatNumber = node.optString("vatNumber"),
        )

        /** `Option<PaymentAuthorizationEventData>`: null/missing, or an object with the single key `cVC` (u16). */
        private fun paymentAuthorization(node: JsonNode): Int? {
            val value = node.get("paymentAuthorization")
            if (value == null || value.isNull || value.isMissingNode) return null
            if (!value.isObject || value.size() != 1 || value.get("cVC") == null) {
                throw DataError("unknown variant, expected `cVC`")
            }
            return value.reqUnsigned("cVC", AxumJson.U16_MAX).toInt()
        }
    }
}

/** `OrderItemEventData` (camelCase). */
data class OrderItemEventData(
    val id: UUID,
    val createdAt: Instant,
    val productVariantId: UUID,
    val productVariantVersionId: UUID,
    val taxRateVersionId: UUID,
    val shoppingCartItemId: UUID,
    val count: BigInteger,
    val compensatableAmount: BigInteger,
    val shipmentMethodId: UUID,
    val discountIds: List<UUID>,
) {
    companion object {
        fun fromJson(node: JsonNode) = OrderItemEventData(
            id = node.reqUuid("id"),
            createdAt = node.reqDateTime("createdAt"),
            productVariantId = node.reqUuid("productVariantId"),
            productVariantVersionId = node.reqUuid("productVariantVersionId"),
            taxRateVersionId = node.reqUuid("taxRateVersionId"),
            shoppingCartItemId = node.reqUuid("shoppingCartItemId"),
            count = node.reqUnsigned("count"),
            compensatableAmount = node.reqUnsigned("compensatableAmount"),
            shipmentMethodId = node.reqUuid("shipmentMethodId"),
            discountIds = node.reqArray("discountIds").map { AxumJson.asUuid(it, "discountIds") },
        )
    }
}

/** `VendorAddressEventData`: **snake_case** (no `rename_all`), all fields required strings (defect D5). */
data class VendorAddressEventData(
    val id: UUID,
    val street1: String,
    val street2: String,
    val city: String,
    val postalCode: String,
    val country: String,
    val companyName: String,
) {
    companion object {
        fun fromJson(node: JsonNode) = VendorAddressEventData(
            id = node.reqUuid("id"),
            street1 = node.reqString("street1"),
            street2 = node.reqString("street2"),
            city = node.reqString("city"),
            postalCode = node.reqString("postal_code"),
            country = node.reqString("country"),
            companyName = node.reqString("company_name"),
        )
    }
}

/** `UserEventData`: **snake_case** `first_name`, `last_name`. */
data class UserEventData(val id: UUID, val firstName: String, val lastName: String) {
    companion object {
        fun fromJson(node: JsonNode) =
            UserEventData(node.reqUuid("id"), node.reqString("first_name"), node.reqString("last_name"))
    }
}

/** `UserAddressEventData` (camelCase; `companyName` optional). */
data class UserAddressEventData(
    val id: UUID,
    val street1: String,
    val street2: String,
    val city: String,
    val postalCode: String,
    val country: String,
    val companyName: String?,
    val userId: UUID,
) {
    companion object {
        fun fromJson(node: JsonNode) = UserAddressEventData(
            id = node.reqUuid("id"),
            street1 = node.reqString("street1"),
            street2 = node.reqString("street2"),
            city = node.reqString("city"),
            postalCode = node.reqString("postalCode"),
            country = node.reqString("country"),
            companyName = node.optString("companyName"),
            userId = node.reqUuid("userId"),
        )
    }
}

/** `UserAddressArchivedEventData` (camelCase). */
data class UserAddressArchivedEventData(val id: UUID, val userId: UUID) {
    companion object {
        fun fromJson(node: JsonNode) = UserAddressArchivedEventData(node.reqUuid("id"), node.reqUuid("userId"))
    }
}

/** `Event<T>` envelope: `{topic, data}`; [decodeData] decodes `data` (must be an object). */
fun <T> decodeEnvelope(root: JsonNode, decodeData: (JsonNode) -> T): Pair<String, T> {
    root.req("topic")
    val data = root.reqObject("data")
    return root.reqString("topic") to decodeData(data)
}
