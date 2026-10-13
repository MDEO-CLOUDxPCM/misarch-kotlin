package org.misarch.`return`

import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.misarch.`return`.event.model.ReturnDTO
import org.misarch.`return`.graphql.input.CreateReturnInput
import org.misarch.`return`.persistence.model.OrderEntity
import org.misarch.`return`.persistence.model.OrderItemEntity
import org.misarch.`return`.persistence.model.ProductVariantVersionEntity
import org.misarch.`return`.persistence.model.ReturnEntity
import org.misarch.`return`.persistence.model.ShipmentEntity
import org.misarch.`return`.persistence.repository.OrderItemRepository
import org.misarch.`return`.persistence.repository.OrderRepository
import org.misarch.`return`.persistence.repository.ProductVariantVersionRepository
import org.misarch.`return`.persistence.repository.ReturnRepository
import org.misarch.`return`.persistence.repository.ShipmentRepository
import org.misarch.`return`.service.ReturnService
import org.misarch.common.event.EventJson
import org.misarch.common.event.EventPublisher
import org.misarch.common.graphql.AuthorizedUser
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * Validation order and messages of [ReturnService.createReturn] (they are part of the observable behaviour).
 */
class ReturnServiceTest {

    private val returnRepository = mockk<ReturnRepository>()
    private val orderItemRepository = mockk<OrderItemRepository>()
    private val shipmentRepository = mockk<ShipmentRepository>()
    private val orderRepository = mockk<OrderRepository>()
    private val pvvRepository = mockk<ProductVariantVersionRepository>()
    private val eventPublisher = mockk<EventPublisher>()
    private val service = ReturnService(
        returnRepository, orderItemRepository, shipmentRepository, orderRepository, pvvRepository, eventPublisher
    )

    private val buyer = AuthorizedUser(UUID.randomUUID(), listOf("buyer"))
    private val orderId = UUID.randomUUID()
    private val shipmentId = UUID.randomUUID()
    private val pvvId = UUID.randomUUID()

    private fun item(
        orderId: UUID = this.orderId, sentWithId: UUID? = shipmentId, amount: Long? = 100,
        pvv: UUID? = pvvId, returnedWithId: UUID? = null
    ) = OrderItemEntity(returnedWithId, sentWithId, orderId, amount, pvv, UUID.randomUUID())

    private fun setup(
        items: List<OrderItemEntity>,
        deliveredAt: OffsetDateTime? = OffsetDateTime.now().minusDays(2),
        pvvs: List<ProductVariantVersionEntity> = emptyList(),
        owner: UUID = buyer.id,
    ) {
        every { orderItemRepository.findAllById(any<Iterable<UUID>>()) } answers {
            val ids = firstArg<Iterable<UUID>>().toSet()
            items.filter { it.id in ids }.asFlow()
        }
        coEvery { orderRepository.findById(any()) } answers { OrderEntity(owner, firstArg()) }
        every { shipmentRepository.findAllById(any<Iterable<UUID>>()) } returns flowOf(ShipmentEntity(deliveredAt, shipmentId))
        every { pvvRepository.findAllById(any<Iterable<UUID>>()) } returns pvvs.asFlow()
        val saved = slot<ReturnEntity>()
        coEvery { returnRepository.save(capture(saved)) } answers {
            val r = saved.captured
            ReturnEntity(r.reason, r.orderId, r.refundedAmount, r.createdAt, UUID.randomUUID())
        }
        every { orderItemRepository.saveAll(any<Iterable<OrderItemEntity>>()) } answers { firstArg<Iterable<OrderItemEntity>>().asFlow() }
        coEvery { eventPublisher.publishEvent(any(), any()) } just Runs
    }

    private fun create(ids: List<UUID>, user: AuthorizedUser = buyer) =
        runBlocking { service.createReturn(CreateReturnInput(ids, "broken"), user) }

    private fun assertFails(ids: List<UUID>, type: Class<out Throwable>, message: String, user: AuthorizedUser = buyer) {
        assertThatThrownBy { create(ids, user) }.isInstanceOf(type).hasMessage(message)
        coVerify(exactly = 0) { eventPublisher.publishEvent(any(), any()) }
    }

    @Test
    fun `empty or unknown ids fail in first() before the explicit check`() {
        setup(emptyList())
        assertFails(emptyList(), NoSuchElementException::class.java, "List is empty.")
        assertFails(listOf(UUID.randomUUID()), NoSuchElementException::class.java, "List is empty.")
    }

    @Test
    fun `ownership is checked first and employees are not exempt`() {
        val items = listOf(item())
        setup(items, owner = UUID.randomUUID())
        assertFails(items.map { it.id } + UUID.randomUUID(), IllegalArgumentException::class.java, "Only the original buyer can create returns")
        assertFails(items.map { it.id }, IllegalArgumentException::class.java, "Only the original buyer can create returns",
            AuthorizedUser(UUID.randomUUID(), listOf("admin")))
    }

    @Test
    fun `missing, already returned and incomplete items`() {
        val items = listOf(item(), item(returnedWithId = UUID.randomUUID()), item(sentWithId = null))
        setup(items)
        val unknown = UUID.randomUUID()
        assertFails(listOf(items[0].id, unknown), IllegalArgumentException::class.java, "Order item ids [$unknown] do not exist")
        assertFails(listOf(items[0].id, items[1].id), IllegalArgumentException::class.java,
            "Order item ids [${items[1].id}] have already been returned")
        assertFails(listOf(items[0].id, items[2].id), IllegalArgumentException::class.java,
            "Missing data about order items, please try again later")
    }

    @Test
    fun `undelivered shipment and mixed orders`() {
        val items = listOf(item(), item(orderId = UUID.randomUUID()))
        setup(items, deliveredAt = null)
        assertFails(listOf(items[0].id), IllegalArgumentException::class.java, "At least one order item was not delivered")
        setup(items)
        assertFails(items.map { it.id }, IllegalArgumentException::class.java, "All order items must belong to the same order")
    }

    @Test
    fun `missing product variant version (the default in MiSArch) and return periods`() {
        val items = listOf(item())
        val ids = items.map { it.id }
        setup(items)
        assertFails(ids, IllegalStateException::class.java, "Product variant version not found")
        setup(items, pvvs = listOf(ProductVariantVersionEntity(0, pvvId)))
        assertFails(ids, IllegalArgumentException::class.java, "Order item ${items[0].id} cannot be returned")
        setup(items, deliveredAt = OffsetDateTime.now().minusDays(31), pvvs = listOf(ProductVariantVersionEntity(30, pvvId)))
        assertFails(ids, IllegalArgumentException::class.java, "Order item ${items[0].id} can no longer be returned")
    }

    @Test
    fun `successful return sums the amounts, marks the items and publishes the input id list`() {
        val items = listOf(item(amount = 100), item(amount = 250))
        setup(items, pvvs = listOf(ProductVariantVersionEntity(null, pvvId)))
        val ids = listOf(items[1].id, items[0].id, items[1].id)
        val payload = slot<Any>()
        coEvery { eventPublisher.publishEvent("return/return/created", capture(payload)) } just Runs
        val result = create(ids)
        assertThat(result.refundedAmount).isEqualTo(350)
        assertThat(result.orderId).isEqualTo(orderId)
        assertThat(items.map { it.returnedWithId }).containsOnly(result.id)
        val dto = payload.captured as ReturnDTO
        assertThat(dto.orderItemIds).containsExactlyElementsOf(ids)
        assertThat(dto.refundedAmount).isEqualTo(350L)
    }

    @Test
    fun `event JSON has the original shape`() {
        val id = UUID.randomUUID()
        val itemId = UUID.randomUUID()
        val entity = ReturnEntity("reason", orderId, 5_000_000_000, OffsetDateTime.of(2024, 5, 1, 10, 20, 30, 123_456_000, ZoneOffset.UTC), id)
        assertThat(EventJson.toJson(entity.toEventDTO(listOf(itemId)))).isEqualTo(
            """{"id":"$id","orderId":"$orderId","orderItemIds":["$itemId"],"reason":"reason","refundedAmount":5000000000,"createdAt":"2024-05-01T10:20:30.123456Z"}"""
        )
        // GraphQL Int narrowing (overflow) like the original
        assertThat(entity.toDTO().refundedAmount).isEqualTo(5_000_000_000L.toInt())
    }
}
