package org.misarch.order.service

import org.misarch.order.remote.model.DiscountDTO
import kotlin.math.roundToLong

/**
 * What the buyer pays for an order item: unit price × count, reduced by every applicable discount
 * (`× (1 − discount)`), rounded to the nearest unit. The retail price is the gross price (tax included).
 *
 * @param price the retail price of the product variant version
 * @param count the quantity
 * @param discounts the applicable discounts
 */
fun compensatableAmount(price: Long, count: Long, discounts: List<DiscountDTO>): Long {
    val amount = discounts.fold(price.toDouble() * count.toDouble()) { amount, discount -> amount * (1.0 - discount.discount) }
    return amount.roundToLong()
}

/**
 * The order amount passed to discount (checked against a discount's `minOrderAmount`): Σ unit price × count, capped
 * at `Int.MAX_VALUE` (the GraphQL `Int` of discount's input).
 *
 * @param pricesAndCounts the unit prices and counts of the ordered product variants
 */
fun orderAmount(pricesAndCounts: Collection<Pair<Long, Long>>): Int =
    pricesAndCounts.sumOf { (price, count) -> price * count }.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
