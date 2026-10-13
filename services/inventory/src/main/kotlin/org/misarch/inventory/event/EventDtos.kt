package org.misarch.inventory.event

import org.misarch.common.nest.ClassValidator
import org.misarch.common.nest.ClassValidator.Nested
import org.misarch.common.nest.ClassValidator.Property
import org.misarch.common.nest.ClassValidator.Schema

/**
 * The class-validator decorators of the original event DTOs (inventory/src/events/dto). Constraints per property are
 * listed bottom-up (registration order), properties in declaration order.
 */
object EventDtos {

    private val uuid = { ClassValidator.isUUID() }

    /** `OrderItemDTO` (inventory version: dates are `IsDateString`). */
    val ORDER_ITEM: Schema = Schema(
        listOf(
            Property("id", listOf(uuid())),
            Property("createdAt", listOf(ClassValidator.isDateString())),
            Property("productVariantVersionId", listOf(uuid())),
            Property("productVariantId", listOf(uuid())),
            Property("taxRateVersionId", listOf(uuid())),
            Property("shoppingCartItemId", listOf(uuid())),
            // @IsInt() @Min(1)
            Property("count", listOf(ClassValidator.min(1), ClassValidator.isInt())),
            // @IsInt() @Min(0)
            Property("compensatableAmount", listOf(ClassValidator.min(0), ClassValidator.isInt())),
            Property("shipmentMethodId", listOf(uuid())),
            // @IsArray() @IsUUID("4", { each: true })
            Property("discountIds", listOf(ClassValidator.isUUID(each = true, version4 = true), ClassValidator.isArray())),
        )
    )

    private val PAYMENT_AUTHORIZATION: Schema = Schema(listOf(Property("CVC", listOf(ClassValidator.isNumber()))))

    /** `OrderDTO` (inventory version). */
    val ORDER: Schema = Schema(
        listOf(
            Property("id", listOf(uuid())),
            Property("userId", listOf(uuid())),
            Property("createdAt", listOf(ClassValidator.isDateString())),
            Property("orderStatus", listOf(ClassValidator.isEnum(listOf("PENDING", "PLACED", "REJECTED")))),
            Property("compensatableOrderAmount", listOf(ClassValidator.isInt())),
            Property("placedAt", listOf(ClassValidator.isDateString()), optional = true),
            Property(
                "rejectionReason",
                listOf(ClassValidator.isEnum(listOf("INVALID_ORDER_DATA", "INVENTORY_RESERVATION_FAILED"))),
                optional = true
            ),
            // @ValidateNested({ each: true }) @Type(() => OrderItemDTO) @IsArray()
            Property("orderItems", listOf(ClassValidator.isArray()), nested = Nested({ ORDER_ITEM }, each = true)),
            Property("shipmentAddressId", listOf(uuid())),
            Property("invoiceAddressId", listOf(uuid())),
            Property("paymentInformationId", listOf(uuid())),
            // @ValidateNested() @Type(() => PaymentAuthorization) — the real payload uses `paymentAuthorization`
            Property("payment_authorization", nested = Nested({ PAYMENT_AUTHORIZATION }, each = false)),
        )
    )

    /** `PaymentEnabledDto` / `PaymentFailedDto`: `@IsObject() order`. */
    val PAYMENT_EVENT: Schema = Schema(listOf(Property("order", listOf(ClassValidator.isObject()))))

    /** `ShipmentStatusUpdatedDto` (string enum version, used by both shipment routes). */
    val SHIPMENT: Schema = Schema(
        listOf(
            Property("id", listOf(ClassValidator.isString())),
            Property("orderId", listOf(ClassValidator.isString()), optional = true),
            Property("returnId", listOf(ClassValidator.isString()), optional = true),
            Property("status", listOf(ClassValidator.isEnum(listOf("PENDING", "IN_PROGRESS", "DELIVERED", "FAILED")))),
            Property("orderItemIds", listOf(ClassValidator.isArray())),
        )
    )
}
