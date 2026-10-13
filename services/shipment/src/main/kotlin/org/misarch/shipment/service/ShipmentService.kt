package org.misarch.shipment.service

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactor.awaitSingle
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
import org.misarch.shipment.persistence.model.AddressEntity
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
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.await
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
 * @property entityTemplate inserts shipments with a pre-generated id
 * @property databaseClient transaction-scoped advisory locks
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
    private val experimentConfigService: ExperimentConfigService,
    private val entityTemplate: R2dbcEntityTemplate,
    private val databaseClient: DatabaseClient
) {

    /**
     * Logger to use for this service
     */
    private val logger = LoggerFactory.getLogger(ShipmentService::class.java)

    /**
     * Create a shipment for an order and publishes a `shipment/shipment/created` event on success.
     * Publishes a `shipment/shipment/creation-failed` event on failure.
     * Idempotent (fix, docs/fixes/shipment.md): a redelivered `payment-enabled` of an order that already has
     * shipments does nothing (the original created and registered a second set of shipments).
     *
     * @param paymentEnabledDTO the payment enabled DTO
     */
    suspend fun createShipmentForOrder(paymentEnabledDTO: PaymentEnabledDTO) {
        val order = paymentEnabledDTO.order
        lockForTransaction("order:${order.id}")
        if (repository.existsByOrderId(order.id)) {
            logger.info("Shipments for order {} already exist, ignoring the redelivered event", order.id)
            return
        }
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
     * Publishes a `shipment/shipment/creation-failed` event (with the `returnId`) on failure and acknowledges the
     * event - also for the errors before the shipment creation (no vendor address, no shipment method, no known
     * order item, unknown product variant version), which the original threw (HTTP 500, endless redelivery).
     * Idempotent: a redelivered event of a return that already has a shipment does nothing.
     *
     * @param returnDTO the return DTO
     */
    suspend fun createShipmentForReturn(returnDTO: ReturnDTO) {
        lockForTransaction("return:${returnDTO.id}")
        if (repository.existsByReturnId(returnDTO.id)) {
            logger.info("A shipment for return {} already exists, ignoring the redelivered event", returnDTO.id)
            return
        }
        var vendorAddressId: UUID? = null
        var shipmentMethodId: UUID? = null
        val input = try {
            val vendorAddress = addressRepository.findCurrentVendorAddress()
            require(vendorAddress != null) { "No vendor address found" }
            vendorAddressId = vendorAddress.id
            val orderItems = orderItemRepository.findAllById(returnDTO.orderItemIds).toList()
            // archived methods "can no longer be used" (SDL) - the original also considered them (fix)
            val shipmentMethod = shipmentMethodService.findLeastExpensiveShipmentMethod(orderItems)
            shipmentMethodId = shipmentMethod.id
            CreateShipmentInput(
                orderId = null,
                returnId = returnDTO.id,
                shipmentMethodId = shipmentMethod.id!!,
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
            return
        }
        createShipment(input) { _, _ -> }
    }

    /**
     * Creates a shipment and publishes a `shipment/shipment/created` event on success.
     * Publishes a `shipment/shipment/creation-failed` event on failure. Since the fix (docs/fixes/shipment.md) all
     * checks and the provider registration run before the first insert, so a failure leaves no PENDING shipment
     * behind (the original committed one that was never registered).
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
     * Does not handle failures. The checks run in the original's order (address, shipment method, quantity and
     * product variant versions, provider), but all of them before the shipment is stored: the shipment id is
     * generated here, the shipment is registered with the provider, and only then the rows are inserted.
     *
     * @param input the input for creating a shipment
     * @param initOrderItems the function to initialize the order items if necessary
     */
    private suspend fun tryCreateShipment(input: CreateShipmentInput, initOrderItems: InitOrderItems) {
        require(addressRepository.existsById(input.addressId)) { "Address does not exist" }
        // original: findById(...).awaitSingle() -> NoSuchElementException without message ("Unknown reason")
        val shipmentMethod = shipmentMethodRepository.findById(input.shipmentMethodId) ?: throw NoSuchElementException()
        val (quantity, weight) = shipmentMethodService.calculateQuantityAndWeight(input.orderItems.map {
            ProductVariantVersionWithQuantityInput(
                it.productVariantVersionId, it.quantity
            )
        })
        val address = addressRepository.findById(input.addressId) ?: throw NoSuchElementException()
        val shipmentId = UUID.randomUUID()
        sendShipmentToExternalProvider(shipmentId, shipmentMethod, quantity, weight, address)
        val savedShipment = entityTemplate.insert(
            ShipmentEntity(
                status = ShipmentStatus.PENDING,
                shipmentMethodId = input.shipmentMethodId,
                shipmentAddressId = input.addressId,
                orderId = input.orderId,
                returnId = input.returnId,
                id = shipmentId
            )
        ).awaitSingle()
        initOrderItems(input.orderItems, shipmentId)
        shipmentToOrderItemRepository.saveAll(input.orderItems.map {
            ShipmentToOrderItemEntity(
                shipmentId = shipmentId, orderItemId = it.id, id = null
            )
        }).toList()
        eventPublisher.publishEvent(
            ShipmentEvents.SHIPMENT_CREATED, ShipmentDTO(
                id = shipmentId,
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
     * Takes a transaction-scoped Postgres advisory lock on [key], so that concurrent deliveries of the same event
     * (Dapr redelivers a message that is pending too long) are processed one after another.
     *
     * @param key the key to lock (hashed to a 32-bit lock id)
     */
    private suspend fun lockForTransaction(key: String) {
        databaseClient.sql("SELECT pg_advisory_xact_lock(hashtext(:key))").bind("key", key).await()
    }

    /**
     * Sends information about a shipment to an external provider
     *
     * @param shipmentId the (not yet stored) id of the shipment
     * @param shipmentMethod the shipment method
     * @param quantity the number of items
     * @param weight the weight of the items
     * @param address the address the shipment is sent to
     */
    private suspend fun sendShipmentToExternalProvider(
        shipmentId: UUID, shipmentMethod: ShipmentMethodEntity, quantity: Int, weight: Double, address: AddressEntity
    ) {
        // a new WebClient (default codecs: Jackson, nulls included) per shipment, like the original
        val webClient = WebClient.create()
        val shipmentDefinition = ShipmentProviderShipmentDefinition(
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
