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

/**
 * `OrderEventData` (camelCase). FIXED (docs/fixes/invoice.md, D2): the order as discount publishes it is accepted - enum
 * values as Jackson names (`PLACED`, `INVALID_ORDER_DATA`; the original's serde names `Placed`, … are accepted too),
 * `rejectionReason`/`vatNumber`/`paymentAuthorization` may be omitted, `paymentAuthorization` is `{"cvc": n}` (or the
 * former `{"cVC": n}`). The original accepted only the serde forms, so every real event was answered 422 forever.
 */
data class OrderEventData(
    val id: UUID,
    val userId: UUID,
    val createdAt: Instant,
    /** `PENDING`, `PLACED` or `REJECTED` (normalized to the Jackson names). */
    val orderStatus: String,
    val placedAt: Instant,
    /** `INVALID_ORDER_DATA` / `INVENTORY_RESERVATION_FAILED`, or null. */
    val rejectionReason: String?,
    val orderItems: List<OrderItemEventData>,
    val shipmentAddressId: UUID,
    val invoiceAddressId: UUID,
    val compensatableOrderAmount: BigInteger,
    val paymentInformationId: UUID,
    /** `{"cvc": n}` (or the former `{"cVC": n}`); null if absent. */
    val paymentAuthorizationCvc: Int?,
    val vatNumber: String?,
) {
    companion object {
        private val ORDER_STATUS = mapOf(
            "PENDING" to "PENDING", "PLACED" to "PLACED", "REJECTED" to "REJECTED",
            "Pending" to "PENDING", "Placed" to "PLACED", "Rejected" to "REJECTED",
        )
        private val REJECTION_REASON = mapOf(
            "INVALID_ORDER_DATA" to "INVALID_ORDER_DATA", "INVENTORY_RESERVATION_FAILED" to "INVENTORY_RESERVATION_FAILED",
            "InvalidOrderData" to "INVALID_ORDER_DATA", "InventoryReservationFailed" to "INVENTORY_RESERVATION_FAILED",
        )

        fun fromJson(node: JsonNode) = OrderEventData(
            id = node.reqUuid("id"),
            userId = node.reqUuid("userId"),
            createdAt = node.reqDateTime("createdAt"),
            orderStatus = node.reqString("orderStatus").let {
                ORDER_STATUS[it] ?: throw DataError("unknown variant `$it`, expected one of `PENDING`, `PLACED`, `REJECTED`")
            },
            placedAt = node.reqDateTime("placedAt"),
            rejectionReason = node.optString("rejectionReason")?.let {
                REJECTION_REASON[it] ?: throw DataError("unknown variant `$it`")
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

        /** null/missing, or an object with the key `cvc` (or the former `cVC`), an unsigned 16-bit integer. */
        private fun paymentAuthorization(node: JsonNode): Int? {
            val value = node.get("paymentAuthorization")
            if (value == null || value.isNull || value.isMissingNode) return null
            if (!value.isObject) throw DataError("invalid type for `paymentAuthorization`")
            val key = listOf("cvc", "cVC").firstOrNull { value.get(it) != null } ?: throw DataError("missing field `cvc`")
            return value.reqUnsigned(key, AxumJson.U16_MAX).toInt()
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

/**
 * `VendorAddressEventData`. FIXED (docs/fixes/invoice.md, D5): the camelCase payload of the address service
 * (`postalCode`, `companyName` nullable, `name` ignored) is accepted; the original's snake_case keys still work.
 */
data class VendorAddressEventData(
    val id: UUID,
    val street1: String,
    val street2: String,
    val city: String,
    val postalCode: String,
    val country: String,
    val companyName: String?,
) {
    companion object {
        fun fromJson(node: JsonNode) = VendorAddressEventData(
            id = node.reqUuid("id"),
            street1 = node.reqString("street1"),
            street2 = node.reqString("street2"),
            city = node.reqString("city"),
            postalCode = node.reqString(if (node.get("postalCode") != null) "postalCode" else "postal_code"),
            country = node.reqString("country"),
            companyName = node.optString(if (node.get("companyName") != null) "companyName" else "company_name"),
        )
    }
}

/**
 * `UserEventData`. FIXED (docs/fixes/invoice.md, D1): the camelCase payload of the user service (`firstName`,
 * `lastName`) is accepted; the original's snake_case keys still work.
 */
data class UserEventData(val id: UUID, val firstName: String, val lastName: String) {
    companion object {
        fun fromJson(node: JsonNode) = UserEventData(
            node.reqUuid("id"),
            node.reqString(if (node.get("firstName") != null) "firstName" else "first_name"),
            node.reqString(if (node.get("lastName") != null) "lastName" else "last_name"),
        )
    }
}

/** `UserAddressEventData` (camelCase; `companyName` optional; `name.firstName/lastName` optional, FIXED: kept). */
data class UserAddressEventData(
    val id: UUID,
    val street1: String,
    val street2: String,
    val city: String,
    val postalCode: String,
    val country: String,
    val companyName: String?,
    val userId: UUID,
    val firstName: String? = null,
    val lastName: String? = null,
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
            firstName = node.get("name")?.takeIf { it.isObject }?.optString("firstName"),
            lastName = node.get("name")?.takeIf { it.isObject }?.optString("lastName"),
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
