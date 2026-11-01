package org.misarch.order

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.misarch.order.remote.model.DiscountDTO
import org.misarch.order.service.compensatableAmount
import org.misarch.order.service.orderAmount
import java.util.UUID

/**
 * Unit tests of the amounts of an order.
 */
class OrderAmountsTest {

    private fun discount(value: Double) = DiscountDTO(UUID.randomUUID(), value)

    @Test
    fun `item amounts are price x count x (1 - discount), rounded`() {
        assertThat(compensatableAmount(20, 1, listOf(discount(0.2)))).isEqualTo(16)
        assertThat(compensatableAmount(20, 3, emptyList())).isEqualTo(60)
        assertThat(compensatableAmount(99, 1, listOf(discount(0.5), discount(0.5)))).isEqualTo(25) // 24.75
        assertThat(compensatableAmount(10, 1, listOf(discount(0.7)))).isEqualTo(3) // 2.9999999999999996
        assertThat(compensatableAmount(10, 2, listOf(discount(1.0)))).isEqualTo(0)
    }

    @Test
    fun `the order amount is the sum of price x count, capped at Int MAX_VALUE`() {
        assertThat(orderAmount(listOf(20L to 1L, 30L to 2L))).isEqualTo(80)
        assertThat(orderAmount(emptyList())).isEqualTo(0)
        assertThat(orderAmount(listOf(0xFFFF_FFFFL to 1L, 2L to 1L))).isEqualTo(Int.MAX_VALUE)
    }

}
