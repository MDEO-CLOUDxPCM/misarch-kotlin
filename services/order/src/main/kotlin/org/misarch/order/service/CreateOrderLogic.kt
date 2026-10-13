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

/**
 * `calculate_order_amount`: the order amount passed to discount (`minOrderAmount` check) = Σ unit price × count.
 * FIXED (docs/fixes/order.md): the original summed the unit prices without the counts (u32, wrapping). Saturates at
 * `Int.MAX_VALUE` (discount's `orderAmount` is a GraphQL `Int`).
 */
fun orderAmount(pricesAndCounts: Collection<Pair<Long, Long>>): Long =
    pricesAndCounts.fold(0L) { acc, (price, count) ->
        val sum = acc + U64.toI64(price) * U64.toI64(count)
        if (sum < 0 || sum > Int.MAX_VALUE) Int.MAX_VALUE.toLong() else sum
    }

/** Rust `BTreeSet<Discount>` from an iterator: unique by id (the last one wins), ascending byte order. */
fun discountSet(discounts: List<DiscountRecord>): List<DiscountRecord> {
    val byId = LinkedHashMap<UUID, DiscountRecord>()
    discounts.forEach { byId[it.id] = it }
    return byId.values.sortedWith { a, b -> RustUuid.ORDER.compare(a.id, b.id) }
}

/**
 * `calculate_compensatable_amount`: what the buyer pays for the item = unit price × count, reduced by every applicable
 * discount (`× (1 − discount)`, ascending discount-id order), rounded to the nearest unit (half up).
 * FIXED (docs/fixes/order.md): the original multiplied by the discount fraction itself (a 10 % discount charged 10 %
 * of the price, no discount charged the full price), ignored the count and truncated. The retail price is the gross
 * price (tax included); shipment fees are added once per order (`compensatableOrderAmount`).
 */
fun compensatableAmount(price: Long, count: Long, discounts: List<DiscountRecord>): Long {
    var amount = U64.toI64(price).toDouble() * U64.toI64(count).toDouble()
    for (discount in discounts.sortedWith { a, b -> RustUuid.ORDER.compare(a.id, b.id) }) {
        amount *= 1.0 - discount.discount
    }
    return U64.fromF64(Math.round(amount).toDouble())
}
