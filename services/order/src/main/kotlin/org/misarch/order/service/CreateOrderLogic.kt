package org.misarch.order.service

import org.misarch.order.persistence.model.DiscountRecord
import org.misarch.order.rust.RustError
import org.misarch.order.rust.RustMessages
import org.misarch.order.rust.RustTypeNames
import org.misarch.order.rust.U64
import org.misarch.common.rust.RustUuid
import java.util.UUID

/* Pure parts of `createOrder` (mutation.rs, order_item.rs), unit tested in CreateOrderLogicTest. */

/** An `OrderItemInput` as the original holds it (`coupon_ids: HashSet`). */
data class NormalizedOrderItemInput(
    val shoppingCartItemId: UUID,
    val shipmentMethodId: UUID,
    val couponIds: Set<UUID>,
)

/**
 * Rust `BTreeSet<OrderItemInput>::from_iter` (Ord = `shoppingCartItemId`, Eq = all fields): stable sort by
 * `shoppingCartItemId` (byte order), then an element is dropped when the *next* one is fully equal - adjacent full
 * duplicates collapse, entries with the same cart item but other differences are all kept.
 */
fun normalizeOrderItemInputs(inputs: List<NormalizedOrderItemInput>): List<NormalizedOrderItemInput> {
    val sorted = inputs.sortedWith { a, b -> RustUuid.ORDER.compare(a.shoppingCartItemId, b.shoppingCartItemId) }
    return sorted.filterIndexed { i, input -> i + 1 >= sorted.size || sorted[i + 1] != input }
}

/** `validate_objects`: reports the LAST missing id in list order. */
fun validateAllPresent(ids: List<UUID>, present: Set<UUID>, typeName: String) {
    val lastMissing = ids.lastOrNull { it !in present }
    if (lastMissing != null) throw RustError(RustMessages.notPresent(typeName, lastMissing))
}

/**
 * `build_counts_by_product_variant_ids` + `build_order_item_inputs_by_product_variant_ids`: maps the inputs (sorted)
 * to the cart lines; the first input whose cart item is not in the cart fails; the last input wins per product variant.
 */
fun mapInputsToProductVariants(
    inputs: List<NormalizedOrderItemInput>,
    cartLines: Map<UUID, Pair<UUID, Long>>,
): Pair<LinkedHashMap<UUID, Long>, LinkedHashMap<UUID, NormalizedOrderItemInput>> {
    val counts = LinkedHashMap<UUID, Long>()
    val inputsByProductVariant = LinkedHashMap<UUID, NormalizedOrderItemInput>()
    for (input in inputs) {
        val (productVariantId, count) = cartLines[input.shoppingCartItemId]
            ?: throw RustError(RustMessages.hashMapError(RustTypeNames.UUID_U64_TUPLE, input.shoppingCartItemId))
        counts[productVariantId] = count
        inputsByProductVariant[productVariantId] = input
    }
    return counts to inputsByProductVariant
}

/**
 * `calculate_availability_of_product_variant_ids`: every expected variant must be in the inventory answer and have
 * at least the expected count (u64 comparison).
 */
fun checkAvailability(stockCounts: Map<UUID, Long>, expectedCounts: Map<UUID, Long>) {
    val available = expectedCounts.map { (id, expected) ->
        val count = stockCounts[id] ?: throw RustError(RustMessages.hashMapError(RustTypeNames.U64, id))
        U64.ge(count, expected)
    }
    if (!available.all { it }) throw RustError("Not all requested product variants are available.")
}

/** `calculate_order_amount`: u32 sum of the unit prices (wrapping), not multiplied by count. */
fun orderAmount(prices: Collection<Long>): Long = prices.fold(0L) { acc, price -> (acc + price) and 0xFFFF_FFFFL }

/** Rust `BTreeSet<Discount>` from an iterator: unique by id (the last one wins), ascending byte order. */
fun discountSet(discounts: List<DiscountRecord>): List<DiscountRecord> {
    val byId = LinkedHashMap<UUID, DiscountRecord>()
    discounts.forEach { byId[it.id] = it }
    return byId.values.sortedWith { a, b -> RustUuid.ORDER.compare(a.id, b.id) }
}

/**
 * `calculate_compensatable_amount`: `price as f64` multiplied by every discount factor (ascending discount-id order),
 * truncated with `as u64`. Note: the original multiplies by the discount fraction itself, ignores the count, taxes and
 * shipment fees (reproduced, see docs/specs/order.md §12).
 */
fun compensatableAmount(price: Long, discounts: List<DiscountRecord>): Long {
    var amount = price.toDouble()
    for (discount in discounts.sortedWith { a, b -> RustUuid.ORDER.compare(a.id, b.id) }) {
        amount *= discount.discount
    }
    return U64.fromF64(amount)
}
