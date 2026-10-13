package org.misarch.shipment.service

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.misarch.common.event.EventPublisher
import org.misarch.shipment.ecs.ExperimentConfigService
import org.misarch.shipment.event.ShipmentEvents
import org.misarch.shipment.event.model.PaymentEnabledDTO
import org.misarch.shipment.event.model.ReturnDTO
import org.misarch.shipment.event.model.ShipmentCreationFailedDTO
import org.misarch.shipment.event.model.ShipmentDTO
import org.misarch.shipment.event.model.ShipmentStatusUpdatedDTO
import org.misarch.shipment.graphql.input.ProductVariantVersionWithQuantityInput
import org.misarch.shipment.graphql.model.ShipmentStatus
import org.misarch.shipment.persistence.model.ShipmentEntity
import org.misarch.shipment.persistence.model.ShipmentMethodEntity
import org.misarch.shipment.persistence.model.ShipmentToOrderItemEntity
import org.misarch.shipment.persistence.repository.AddressRepository
import org.misarch.shipment.persistence.repository.OrderItemRepository
import org.misarch.shipment.persistence.repository.ShipmentMethodRepository
import org.misarch.shipment.persistence.repository.ShipmentRepository
import org.misarch.shipment.persistence.repository.ShipmentToOrderItemRepository
import org.misarch.shipment.provider.AddressDefinition
import org.misarch.shipment.provider.ShipmentProviderConfigurationProperties
import org.misarch.shipment.provider.ShipmentProviderShipmentDefinition
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import java.util.UUID

/**
 * Service for [ShipmentEntity]s
 *
 * @param repository the provided repository
 * @param addressRepository used for address validation
 * @param shipmentToOrderItemRepository used for creating the many-to-many relationship between shipments and order items
 * @param orderItemRepository used for creating order items if they do not exist
 * @param shipmentMethodRepository used for getting the shipment method
 * @property shipmentMethodService quantity/weight calculation, cheapest method
 * @property eventPublisher the event publisher
 * @property shipmentProviderConfigurationProperties the configuration properties for the external shipment provider
 * @property experimentConfigService the experiment configuration service providing configuration values
 */
@Service
class ShipmentService(
    private val repository: ShipmentRepository,
    private val addressRepository: AddressRepository,
    private val shipmentToOrderItemRepository: ShipmentToOrderItemRepository,
    private val orderItemRepository: OrderItemRepository,
    private val shipmentMethodRepository: ShipmentMethodRepository,
    private val shipmentMethodService: ShipmentMethodService,
    private val eventPublisher: EventPublisher,
    private val shipmentProviderConfigurationProperties: ShipmentProviderConfigurationProperties,
    private val experimentConfigService: ExperimentConfigService
) {

    /**
     * Logger to use for this service
     */
    private val logger = LoggerFactory.getLogger(ShipmentService::class.java)

    /**
     * Create a shipment for an order and publishes a `shipment/shipment/created` event on success.
     * Publishes a `shipment/shipment/creation-failed` event on failure.
     *
     * @param paymentEnabledDTO the payment enabled DTO
     */
    suspend fun createShipmentForOrder(paymentEnabledDTO: PaymentEnabledDTO) {
        val order = paymentEnabledDTO.order
        val groupedOrderItems = order.orderItems.groupBy { it.shipmentMethodId }
        for ((shipmentMethodId, orderItems) in groupedOrderItems) {
            createShipment(
                CreateShipmentInput(orderId = order.id,
                    returnId = null,
                    shipmentMethodId = shipmentMethodId,
                    addressId = order.shipmentAddressId,
                    orderItems = orderItems.map { OrderItemInput(it.id, it.productVariantVersionId, it.count.toInt()) }
                )
            ) { items, shipmentId ->
                items.forEach {
                    orderItemRepository.createOrderItem(it.id, shipmentId, it.productVariantVersionId, it.quantity)
                }
            }
        }
    }

    /**
     * Create a shipment for a return and publishes a `shipment/shipment/created` event on success.
     * Publishes a `shipment/shipment/creation-failed` event on failure.
     * Errors before the shipment creation (no vendor address, no shipment method, no known order item) are thrown
     * (HTTP 500, Dapr redelivers), like in the original.
     *
     * @param returnDTO the return DTO
     */
    suspend fun createShipmentForReturn(returnDTO: ReturnDTO) {
        val vendorAddress = addressRepository.findCurrentVendorAddress()
        require(vendorAddress != null) { "No vendor address found" }
        val orderItems = orderItemRepository.findAllById(returnDTO.orderItemIds).toList()
        val shipmentMethod = shipmentMethodService.findLeastExpensiveShipmentMethod(orderItems)
        createShipment(
            CreateShipmentInput(
                orderId = null,
                returnId = returnDTO.id,
                shipmentMethodId = shipmentMethod.id!!,
                addressId = vendorAddress.id,
                orderItems = orderItems.map { OrderItemInput(it.id, it.productVariantVersionId, it.quantity) }
            )
        ) { _, _ -> }
    }

    /**
     * Creates a shipment and publishes a `shipment/shipment/created` event on success.
     * Publishes a `shipment/shipment/creation-failed` event on failure (rows written before the failure stay, the
     * surrounding transaction commits - like the original).
     *
     * @param input the input for creating a shipment
     * @param initOrderItems the function to initialize the order items if necessary
     */
    suspend fun createShipment(input: CreateShipmentInput, initOrderItems: InitOrderItems) {
        try {
            tryCreateShipment(input, initOrderItems)
        } catch (e: Exception) {
            eventPublisher.publishEvent(
                ShipmentEvents.SHIPMENT_CREATION_FAILED, ShipmentCreationFailedDTO(
                    orderId = input.orderId,
                    returnId = input.returnId,
                    reason = e.message ?: "Unknown reason",
                    shipmentAddressId = input.addressId,
                    orderItemIds = input.orderItems.map { it.id },
                    shipmentMethodId = input.shipmentMethodId
                )
            )
        }
    }

    /**
     * Creates a shipment and publishes a `shipment/shipment/created` event on success.
     * Does not handle failures
     *
     * @param input the input for creating a shipment
     * @param initOrderItems the function to initialize the order items if necessary
     */
    private suspend fun tryCreateShipment(input: CreateShipmentInput, initOrderItems: InitOrderItems) {
        require(addressRepository.existsById(input.addressId)) { "Address does not exist" }
        // original: findById(...).awaitSingle() -> NoSuchElementException without message ("Unknown reason")
        val shipmentMethod = shipmentMethodRepository.findById(input.shipmentMethodId) ?: throw NoSuchElementException()
        val shipment = ShipmentEntity(
            status = ShipmentStatus.PENDING,
            shipmentMethodId = input.shipmentMethodId,
            shipmentAddressId = input.addressId,
            orderId = input.orderId,
            returnId = input.returnId,
            id = null
        )
        val savedShipment = repository.save(shipment)
        initOrderItems(input.orderItems, savedShipment.id!!)
        shipmentToOrderItemRepository.saveAll(input.orderItems.map {
            ShipmentToOrderItemEntity(
                shipmentId = savedShipment.id, orderItemId = it.id, id = null
            )
        }).toList()
        sendShipmentToExternalProvider(input, savedShipment, shipmentMethod)
        eventPublisher.publishEvent(
            ShipmentEvents.SHIPMENT_CREATED, ShipmentDTO(
                id = savedShipment.id,
                orderId = input.orderId,
                returnId = input.returnId,
                status = savedShipment.status,
                orderItemIds = input.orderItems.map { it.id },
                shipmentMethodId = input.shipmentMethodId,
                shipmentAddressId = input.addressId
            )
        )
    }

    /**
     * Sends information about a shipment to an external provider
     *
     * @param input the input for creating a shipment
     * @param savedShipment the saved shipment
     * @param shipmentMethod the shipment method
     */
    private suspend fun sendShipmentToExternalProvider(
        input: CreateShipmentInput, savedShipment: ShipmentEntity, shipmentMethod: ShipmentMethodEntity
    ) {
        val (quantity, weight) = shipmentMethodService.calculateQuantityAndWeight(input.orderItems.map {
            ProductVariantVersionWithQuantityInput(
                it.productVariantVersionId, it.quantity
            )
        })
        val address = addressRepository.findById(input.addressId) ?: throw NoSuchElementException()
        // a new WebClient (default codecs: Jackson, nulls included) per shipment, like the original
        val webClient = WebClient.create()
        val shipmentDefinition = ShipmentProviderShipmentDefinition(
            shipmentId = savedShipment.id!!,
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
        sendShipmentToExternalProviderWithRetries(webClient, shipmentDefinition)
    }

    /**
     * Sends a shipment to an external provider with retries: `providerRetries` attempts with a delay of
     * `providerRetryDelay` ms after each failure, then a last attempt whose failure propagates
     * (i.e. `providerRetries + 1` attempts in total).
     *
     * @param webClient the web client to use
     * @param shipmentDefinition the shipment definition to send
     */
    private suspend fun sendShipmentToExternalProviderWithRetries(
        webClient: WebClient,
        shipmentDefinition: ShipmentProviderShipmentDefinition
    ) {
        repeat(experimentConfigService.providerRetries) {
            try {
                webClient.post().uri(shipmentProviderConfigurationProperties.endpoint).bodyValue(
                    shipmentDefinition
                ).retrieve().toBodilessEntity().awaitSingleOrNull()
                return
            } catch (e: Exception) {
                logger.error("Failed to send shipment to external provider, retry", e)
                delay(experimentConfigService.providerRetryDelay.toLong())
            }
        }
        webClient.post().uri(shipmentProviderConfigurationProperties.endpoint).bodyValue(
            shipmentDefinition
        ).retrieve().toBodilessEntity().awaitSingleOrNull()
    }

    /**
     * Updates the status of a shipment and publishes a `shipment/shipment/status-updated` event
     * (any transition is accepted, no transaction - like the original)
     *
     * @param shipmentId unique identifier of the shipment
     * @param status the new status of the shipment
     */
    suspend fun updateShipmentStatus(shipmentId: UUID, status: ShipmentStatus) {
        val shipment = repository.findById(shipmentId) ?: throw NoSuchElementException()
        shipment.status = status
        repository.save(shipment)
        eventPublisher.publishEvent(
            ShipmentEvents.SHIPMENT_STATUS_UPDATED, ShipmentStatusUpdatedDTO(id = shipmentId,
                status = status,
                orderId = shipment.orderId,
                returnId = shipment.returnId,
                orderItemIds = shipmentToOrderItemRepository.findByShipmentId(shipmentId).map { it.orderItemId })
        )
    }

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

/**
 * Function to initialize order items if required
 * Takes the order items and the shipment id
 */
typealias InitOrderItems = suspend (List<OrderItemInput>, UUID) -> Unit
