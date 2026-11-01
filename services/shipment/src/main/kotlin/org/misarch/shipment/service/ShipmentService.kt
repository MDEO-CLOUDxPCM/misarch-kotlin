package org.misarch.shipment.service

import kotlinx.coroutines.flow.toList
import org.misarch.common.event.EventPublisher
import org.misarch.common.persistence.findByIdOrThrow
import org.misarch.common.persistence.requiredId
import org.misarch.shipment.client.AddressDefinition
import org.misarch.shipment.client.ShipmentProviderShipmentDefinition
import org.misarch.shipment.event.ShipmentEvents
import org.misarch.shipment.event.model.PaymentEnabledDTO
import org.misarch.shipment.event.model.ReturnDTO
import org.misarch.shipment.event.model.ShipmentCreationFailedDTO
import org.misarch.shipment.event.model.ShipmentDTO
import org.misarch.shipment.event.model.ShipmentStatusUpdatedDTO
import org.misarch.shipment.graphql.input.ProductVariantVersionWithQuantityInput
import org.misarch.shipment.graphql.model.ShipmentStatus
import org.misarch.shipment.persistence.model.AddressEntity
import org.misarch.shipment.persistence.model.ShipmentEntity
import org.misarch.shipment.persistence.model.ShipmentMethodEntity
import org.misarch.shipment.persistence.model.ShipmentToOrderItemEntity
import org.misarch.shipment.persistence.model.ShipmentToOrderItemKey
import org.misarch.shipment.persistence.repository.AddressRepository
import org.misarch.shipment.persistence.repository.OrderItemRepository
import org.misarch.shipment.persistence.repository.ShipmentMethodRepository
import org.misarch.shipment.persistence.repository.ShipmentRepository
import org.misarch.shipment.persistence.repository.ShipmentToOrderItemRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Service for [ShipmentEntity]s: stores the shipments of orders and returns (registered at the provider afterwards by
 * [ShipmentRegistrationService]), removes shipments whose registration failed, applies the provider's status updates.
 */
@Service
class ShipmentService(
    private val repository: ShipmentRepository,
    private val addressRepository: AddressRepository,
    private val shipmentToOrderItemRepository: ShipmentToOrderItemRepository,
    private val orderItemRepository: OrderItemRepository,
    private val shipmentMethodRepository: ShipmentMethodRepository,
    private val shipmentMethodService: ShipmentMethodService,
    private val eventPublisher: EventPublisher
) {

    private val logger = LoggerFactory.getLogger(ShipmentService::class.java)

    /**
     * Stores one PENDING shipment per shipment method of an order. A shipment whose checks fail is not stored, a
     * `shipment/shipment/creation-failed` event is published for it instead.
     * Idempotent: a redelivered `payment-enabled` of an order that already has shipments stores nothing.
     *
     * @param paymentEnabledDTO the payment enabled DTO
     * @return the stored shipments, to be registered at the provider
     */
    @Transactional
    suspend fun createShipmentsForOrder(paymentEnabledDTO: PaymentEnabledDTO): List<PendingShipment> {
        val order = paymentEnabledDTO.order
        repository.lockForTransaction("order:${order.id}")
        if (repository.existsByOrderId(order.id)) {
            logger.info("Shipments for order {} already exist, ignoring the redelivered event", order.id)
            return emptyList()
        }
        return order.orderItems.groupBy { it.shipmentMethodId }.mapNotNull { (shipmentMethodId, orderItems) ->
            createShipment(
                CreateShipmentInput(orderId = order.id,
                    returnId = null,
                    shipmentMethodId = shipmentMethodId,
                    addressId = order.shipmentAddressId,
                    orderItems = orderItems.map { OrderItemInput(it.id, it.productVariantVersionId, it.count.toInt()) }
                )
            )
        }
    }

    /**
     * Stores the PENDING shipment of a return (to the newest vendor address, with the cheapest active shipment
     * method). Publishes a `shipment/shipment/creation-failed` event (with the `returnId`) on failure and acknowledges
     * the event - also for the errors before the shipment creation (no vendor address, no shipment method, no known
     * order item, unknown product variant version), so the event is not redelivered.
     * Idempotent: a redelivered event of a return that already has a shipment stores nothing.
     *
     * @param returnDTO the return DTO
     * @return the stored shipment, to be registered at the provider, or null
     */
    @Transactional
    suspend fun createShipmentForReturn(returnDTO: ReturnDTO): PendingShipment? {
        repository.lockForTransaction("return:${returnDTO.id}")
        if (repository.existsByReturnId(returnDTO.id)) {
            logger.info("A shipment for return {} already exists, ignoring the redelivered event", returnDTO.id)
            return null
        }
        var vendorAddressId: UUID? = null
        var shipmentMethodId: UUID? = null
        val input = try {
            val vendorAddress = addressRepository.findCurrentVendorAddress()
            require(vendorAddress != null) { "No vendor address found" }
            vendorAddressId = vendorAddress.id
            val orderItems = orderItemRepository.findAllById(returnDTO.orderItemIds).toList()
            // archived methods "can no longer be used" (SDL) and are not considered
            val shipmentMethod = shipmentMethodService.findLeastExpensiveShipmentMethod(orderItems)
            shipmentMethodId = shipmentMethod.id
            CreateShipmentInput(
                orderId = null,
                returnId = returnDTO.id,
                shipmentMethodId = shipmentMethod.requiredId,
                addressId = vendorAddress.id,
                orderItems = orderItems.map { OrderItemInput(it.id, it.productVariantVersionId, it.quantity) }
            )
        } catch (e: Exception) {
            eventPublisher.publishEvent(
                ShipmentEvents.SHIPMENT_CREATION_FAILED, ShipmentCreationFailedDTO(
                    orderId = null,
                    returnId = returnDTO.id,
                    reason = e.message ?: "Unknown reason",
                    shipmentAddressId = vendorAddressId,
                    orderItemIds = returnDTO.orderItemIds,
                    shipmentMethodId = shipmentMethodId
                )
            )
            return null
        }
        return createShipment(input)
    }

    /**
     * Stores a PENDING shipment with its order item links (order shipments also store their order items). The
     * checks (address, shipment method, quantity and product variant versions, in this order, which determines the
     * `reason` of the event) run before anything is stored; if one fails, a `shipment/shipment/creation-failed` event
     * is published and nothing is stored.
     *
     * @param input the input for creating a shipment
     * @return the stored shipment, or null if a check failed
     */
    private suspend fun createShipment(input: CreateShipmentInput): PendingShipment? {
        val (shipmentMethod, quantity, weight, address) = try {
            checkShipment(input)
        } catch (e: Exception) {
            publishCreationFailed(input, e.message ?: "Unknown reason")
            return null
        }
        val shipment = repository.save(
            ShipmentEntity(
                status = ShipmentStatus.PENDING,
                shipmentMethodId = input.shipmentMethodId,
                shipmentAddressId = input.addressId,
                orderId = input.orderId,
                returnId = input.returnId,
                id = null
            )
        )
        val shipmentId = shipment.requiredId
        if (input.orderId != null) {
            input.orderItems.forEach {
                orderItemRepository.createOrderItem(it.id, shipmentId, it.productVariantVersionId, it.quantity)
            }
        }
        shipmentToOrderItemRepository.saveAll(input.orderItems.map {
            ShipmentToOrderItemEntity(ShipmentToOrderItemKey(shipmentId, it.id))
        }).toList()
        val providerDefinition = ShipmentProviderShipmentDefinition(
            shipmentId = shipmentId,
            ref = shipmentMethod.externalReference,
            quantity = quantity,
            weight = weight,
            address = AddressDefinition(
                street1 = address.street1,
                street2 = address.street2,
                city = address.city,
                postalCode = address.postalCode,
                country = address.country,
                companyName = address.companyName
            )
        )
        return PendingShipment(shipment, input, providerDefinition)
    }

    /**
     * The checks of a new shipment: the address exists, the shipment method exists (`NoSuchElementException` without
     * message, reported as "Unknown reason"), the quantity is positive and the product variant versions are known
     *
     * @param input the input for creating a shipment
     * @return the shipment method, quantity, weight and address of the shipment
     */
    private suspend fun checkShipment(input: CreateShipmentInput): CheckedShipment {
        require(addressRepository.existsById(input.addressId)) { "Address does not exist" }
        val shipmentMethod = shipmentMethodRepository.findByIdOrThrow(input.shipmentMethodId)
        val (quantity, weight) = shipmentMethodService.calculateQuantityAndWeight(input.orderItems.map {
            ProductVariantVersionWithQuantityInput(
                it.productVariantVersionId, it.quantity
            )
        })
        val address = addressRepository.findByIdOrThrow(input.addressId)
        return CheckedShipment(shipmentMethod, quantity, weight, address)
    }

    /**
     * Removes a shipment whose registration at the provider failed (its order item links and the order items it
     * stored) and publishes a `shipment/shipment/creation-failed` event
     *
     * @param shipment the stored shipment
     * @param reason the reason of the failure
     */
    @Transactional
    suspend fun discardShipment(shipment: PendingShipment, reason: String) {
        val shipmentId = shipment.shipment.requiredId
        logger.error("Registration of shipment {} at the provider failed, removing it: {}", shipmentId, reason)
        shipmentToOrderItemRepository.deleteByKeyShipmentId(shipmentId)
        orderItemRepository.deleteBySentWithId(shipmentId)
        repository.deleteById(shipmentId)
        publishCreationFailed(shipment.input, reason)
    }

    private suspend fun publishCreationFailed(input: CreateShipmentInput, reason: String) {
        eventPublisher.publishEvent(
            ShipmentEvents.SHIPMENT_CREATION_FAILED, ShipmentCreationFailedDTO(
                orderId = input.orderId,
                returnId = input.returnId,
                reason = reason,
                shipmentAddressId = input.addressId,
                orderItemIds = input.orderItems.map { it.id },
                shipmentMethodId = input.shipmentMethodId
            )
        )
    }

    /**
     * Updates the status of a shipment and publishes a `shipment/shipment/status-updated` event
     * in one transaction (any transition is accepted)
     *
     * @param shipmentId unique identifier of the shipment
     * @param status the new status of the shipment
     */
    @Transactional
    suspend fun updateShipmentStatus(shipmentId: UUID, status: ShipmentStatus) {
        val shipment = repository.findByIdOrThrow(shipmentId)
        shipment.status = status
        repository.save(shipment)
        eventPublisher.publishEvent(
            ShipmentEvents.SHIPMENT_STATUS_UPDATED, ShipmentStatusUpdatedDTO(id = shipmentId,
                status = status,
                orderId = shipment.orderId,
                returnId = shipment.returnId,
                orderItemIds = shipmentToOrderItemRepository.findByKeyShipmentId(shipmentId).map { it.key.orderItemId })
        )
    }

    /** The results of [checkShipment] */
    private data class CheckedShipment(
        val shipmentMethod: ShipmentMethodEntity,
        val quantity: Int,
        val weight: Double,
        val address: AddressEntity
    )

    /**
     * The shipment of a return
     *
     * @param returnId the return
     * @throws NoSuchElementException if the return has no shipment
     */
    suspend fun findShipmentOfReturn(returnId: UUID): ShipmentEntity =
        repository.findFirstByReturnId(returnId) ?: throw NoSuchElementException()

}

/**
 * A stored PENDING shipment that is registered at the provider next
 *
 * @property shipment the stored shipment
 * @property input the input the shipment was created from
 * @property providerDefinition the shipment as sent to the provider
 */
class PendingShipment(
    val shipment: ShipmentEntity,
    val input: CreateShipmentInput,
    val providerDefinition: ShipmentProviderShipmentDefinition
) {

    /**
     * The payload of `shipment/shipment/created`
     */
    fun toEventDTO(): ShipmentDTO = ShipmentDTO(
        id = shipment.requiredId,
        orderId = input.orderId,
        returnId = input.returnId,
        status = shipment.status,
        orderItemIds = input.orderItems.map { it.id },
        shipmentMethodId = input.shipmentMethodId,
        shipmentAddressId = input.addressId
    )

}

/**
 * Input for creating a shipment
 *
 * @property orderId unique identifier of the order, null if the shipment is a return shipment
 * @property returnId unique identifier of the return, null if the shipment is an order shipment
 * @property shipmentMethodId unique identifier of the shipment method
 * @property addressId unique identifier of the address
 * @property orderItems the items of the shipment
 */
data class CreateShipmentInput(
    val orderId: UUID?,
    val returnId: UUID?,
    val shipmentMethodId: UUID,
    val addressId: UUID,
    val orderItems: List<OrderItemInput>
) {

    init {
        require((orderId != null) != (returnId != null)) { "orderId xor returnId must be set" }
        require(orderItems.isNotEmpty()) { "orderItems must not be empty" }
    }

}

/**
 * Input for an order item
 *
 * @property id unique identifier of the order item
 * @param productVariantVersionId unique identifier of the product variant version
 * @param quantity the quantity of the product variant version
 */
class OrderItemInput(
    val id: UUID,
    productVariantVersionId: UUID,
    quantity: Int
) : ProductVariantVersionWithQuantityInput(productVariantVersionId, quantity)
