package org.misarch.discount

import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.misarch.common.event.EventJson
import org.misarch.discount.event.model.CouponDTO
import org.misarch.discount.event.model.DiscountDTO
import org.misarch.discount.event.model.ReservationSucceededDTO
import org.misarch.discount.event.model.ValidationFailedDTO
import org.misarch.discount.graphql.input.FindApplicableDiscountsProductVariantInput
import org.misarch.discount.persistence.model.CouponEntity
import org.misarch.discount.persistence.model.DiscountEntity
import org.misarch.discount.service.DiscountService
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import tools.jackson.module.kotlin.readValue
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * Unit tests (no database): the per-variant filtering of applicable discounts and the event JSON.
 */
class DiscountServiceTest {

    private val service = DiscountService(
        mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), mockk()
    )

    private fun discount(maxUsagesPerUser: Int? = 10) = DiscountEntity(
        0.1, maxUsagesPerUser, OffsetDateTime.now().plusDays(1), OffsetDateTime.now().minusDays(1), null, UUID.randomUUID()
    )

    private fun coupon(discountId: UUID) = CouponEntity(
        0, 5, OffsetDateTime.now().plusDays(1), OffsetDateTime.now().minusDays(1), "C", discountId, UUID.randomUUID()
    )

    private fun input(count: Int, couponIds: List<UUID> = emptyList()) =
        FindApplicableDiscountsProductVariantInput(UUID.randomUUID(), count, couponIds)

    @Test
    fun `remaining usages are shared and decremented across product variants`() {
        val a = discount()
        val b = discount()
        val remaining = mutableMapOf(a.id!! to 5L, b.id!! to 2L)
        val first = service.filterApplicableDiscounts(listOf(a, b), remaining, input(2), emptyMap())
        assertThat(first.discounts.map { it.id }).containsExactly(a.id, b.id)
        assertThat(remaining).containsEntry(a.id, 3L).containsEntry(b.id, 0L)
        val second = service.filterApplicableDiscounts(listOf(a, b), remaining, input(3), emptyMap())
        assertThat(second.discounts.map { it.id }).containsExactly(a.id)
        assertThat(remaining).containsEntry(a.id, 0L).containsEntry(b.id, 0L)
        assertThat(second.count).isEqualTo(3)
    }

    @Test
    fun `coupons must belong to a usable discount and may not be combined for one discount`() {
        val a = discount()
        val couponA = coupon(a.id!!)
        val couponA2 = coupon(a.id!!)
        val other = coupon(UUID.randomUUID())
        val coupons = listOf(couponA, couponA2, other).associateBy { it.id }
        assertThat(service.filterApplicableDiscounts(listOf(a), mutableMapOf(a.id!! to 5L), input(1, listOf(couponA.id!!)), coupons).discounts)
            .hasSize(1)
        assertThatThrownBy {
            service.filterApplicableDiscounts(listOf(a), mutableMapOf(a.id!! to 5L), input(1, listOf(other.id!!)), coupons)
        }.isInstanceOf(IllegalArgumentException::class.java).hasMessage(
            "Coupon with id ${other.id} could not be used, either due to insufficient remaining usages, the user not owning the coupon, or the coupon not being applicable"
        )
        // insufficient remaining usages make the coupon unusable too
        assertThatThrownBy {
            service.filterApplicableDiscounts(listOf(a), mutableMapOf(a.id!! to 0L), input(1, listOf(couponA.id!!)), coupons)
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            service.filterApplicableDiscounts(listOf(a), mutableMapOf(a.id!! to 5L), input(1, listOf(couponA.id!!, couponA2.id!!)), coupons)
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessage("Coupons with ids [${couponA.id}, ${couponA2.id}] are used multiple times for the same discount ${a.id}")
    }

    @Test
    fun `event payloads omit nulls like the Dapr SDK serializer`() {
        val id = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val discount = DiscountEntity(
            0.2, null, OffsetDateTime.of(2030, 1, 2, 3, 4, 5, 0, ZoneOffset.ofHours(2)),
            OffsetDateTime.of(2020, 1, 1, 0, 0, 0, 120_000_000, ZoneOffset.UTC), null, id
        )
        assertThat(EventJson.toJson(discount.toEventDTO(setOf(id), emptySet(), emptySet()))).isEqualTo(
            """{"id":"$id","discount":0.2,"validUntil":"2030-01-02T03:04:05+02:00","validFrom":"2020-01-01T00:00:00.12Z",""" +
                """"discountAppliesToCategoryIds":["$id"],"discountAppliesToProductIds":[],"discountAppliesToProductVariantIds":[]}"""
        )
        assertThat(EventJson.toJson(DiscountDTO(id, 1.0, 3, "a", "b", 7, emptyList(), emptyList(), emptyList())))
            .contains(""""maxUsagesPerUser":3""").contains(""""minOrderAmount":7""")
        assertThat(EventJson.toJson(CouponDTO(id, null, "u", "f", "CODE", id)))
            .isEqualTo("""{"id":"$id","validUntil":"u","validFrom":"f","code":"CODE","discountId":"$id"}""")
    }

    @Test
    fun `an inbound order is re-serialized with the original DTO fields only`() {
        val inbound = """
            {"order":{"id":"00000000-0000-0000-0000-000000000001","userId":"00000000-0000-0000-0000-000000000002",
            "createdAt":"2026-01-01T00:00:00.5Z","orderStatus":"PLACED","placedAt":"x","rejectionReason":"INVALID_ORDER_DATA",
            "orderItems":[],"shipmentAddressId":"00000000-0000-0000-0000-000000000003",
            "invoiceAddressId":"00000000-0000-0000-0000-000000000004","compensatableOrderAmount":10,
            "paymentInformationId":"00000000-0000-0000-0000-000000000005","vatNumber":"DE1","extra":true}}
        """.trimIndent()
        // like Spring's mapper for request bodies: Kotlin module, unknown properties ignored
        val springLikeMapper = JsonMapper.builder().addModule(KotlinModule.Builder().build())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build()
        val event = springLikeMapper.readValue<ReservationSucceededDTO>(inbound)
        assertThat(EventJson.toJson(ValidationFailedDTO(event.order, listOf(event.order.id)))).isEqualTo(
            """{"order":{"id":"00000000-0000-0000-0000-000000000001","userId":"00000000-0000-0000-0000-000000000002",""" +
                """"createdAt":"2026-01-01T00:00:00.5Z","orderStatus":"PLACED","placedAt":"x","rejectionReason":"INVALID_ORDER_DATA",""" +
                """"orderItems":[],"shipmentAddressId":"00000000-0000-0000-0000-000000000003",""" +
                """"invoiceAddressId":"00000000-0000-0000-0000-000000000004","compensatableOrderAmount":10,""" +
                """"paymentInformationId":"00000000-0000-0000-0000-000000000005","vatNumber":"DE1"},""" +
                """"failingDiscountIds":["00000000-0000-0000-0000-000000000001"]}"""
        )
    }
}
