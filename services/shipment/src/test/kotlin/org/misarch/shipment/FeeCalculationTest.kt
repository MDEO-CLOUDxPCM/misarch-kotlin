package org.misarch.shipment

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.misarch.shipment.persistence.model.ShipmentMethodEntity
import org.misarch.shipment.service.CreateShipmentInput
import org.misarch.shipment.service.OrderItemInput
import org.misarch.shipment.service.ShipmentMethodService
import java.util.UUID

/**
 * The fee formula and its input invariants (Int arithmetic, truncation, overflow).
 */
class FeeCalculationTest {

    private fun method(baseFees: Int, feesPerItem: Int, feesPerKg: Int) =
        ShipmentMethodEntity("n", "d", "r", baseFees, feesPerItem, feesPerKg, null, UUID.randomUUID())

    @Test
    fun `fee is base plus items plus truncated weight part`() {
        assertThat(ShipmentMethodService.calculateFees(method(500, 20, 150), 5, 4.416)).isEqualTo(1262) // 662.4 -> 662
        assertThat(ShipmentMethodService.calculateFees(method(1, 1, 1), 1, 0.333)).isEqualTo(2)
        assertThat(ShipmentMethodService.calculateFees(method(0, 0, 3), 1, -0.5)).isEqualTo(-1) // -1.5 -> -1 (toward zero)
    }

    @Test
    fun `fee arithmetic overflows and saturates like Kotlin Int`() {
        assertThat(ShipmentMethodService.calculateFees(method(Int.MAX_VALUE, 1, 0), 1, 0.0)).isEqualTo(Int.MIN_VALUE)
        assertThat(ShipmentMethodService.calculateFees(method(0, 0, Int.MAX_VALUE), 0, 10.0)).isEqualTo(Int.MAX_VALUE)
        assertThat(ShipmentMethodService.calculateFees(method(0, 0, 1), 0, Double.NaN)).isEqualTo(0)
    }

    @Test
    fun `shipment input requires order xor return and at least one item`() {
        val item = OrderItemInput(UUID.randomUUID(), UUID.randomUUID(), 1)
        assertThatThrownBy { CreateShipmentInput(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), listOf(item)) }
            .hasMessage("orderId xor returnId must be set")
        assertThatThrownBy { CreateShipmentInput(UUID.randomUUID(), null, UUID.randomUUID(), UUID.randomUUID(), emptyList()) }
            .hasMessage("orderItems must not be empty")
    }
}
