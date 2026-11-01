package org.misarch.shipment.service

import org.misarch.common.event.EventPublisher
import org.misarch.shipment.client.ShipmentProviderClient
import org.misarch.shipment.event.ShipmentEvents
import org.misarch.shipment.event.model.PaymentEnabledDTO
import org.misarch.shipment.event.model.ReturnDTO
import org.springframework.stereotype.Service

/**
 * Creation of shipments and their registration at the shipment provider. The shipments are stored by
 * [ShipmentService]'s transactions; the provider calls are not part of a transaction (a database connection would
 * otherwise be held while they run, with retries). A shipment whose registration fails is removed again and
 * `shipment/shipment/creation-failed` is published instead of `shipment/shipment/created`.
 */
@Service
class ShipmentRegistrationService(
    private val shipmentService: ShipmentService,
    private val shipmentProviderClient: ShipmentProviderClient,
    private val eventPublisher: EventPublisher
) {

    /**
     * Creates and registers the shipments of an order whose payment is enabled (one per shipment method)
     *
     * @param paymentEnabledDTO the payment enabled DTO
     */
    suspend fun createShipmentsForOrder(paymentEnabledDTO: PaymentEnabledDTO) {
        shipmentService.createShipmentsForOrder(paymentEnabledDTO).forEach { register(it) }
    }

    /**
     * Creates and registers the shipment of a return
     *
     * @param returnDTO the return DTO
     */
    suspend fun createShipmentForReturn(returnDTO: ReturnDTO) {
        shipmentService.createShipmentForReturn(returnDTO)?.let { register(it) }
    }

    /**
     * Registers a stored shipment and publishes `shipment/shipment/created`; on failure the shipment is removed and
     * `shipment/shipment/creation-failed` is published
     *
     * @param shipment the stored shipment
     */
    private suspend fun register(shipment: PendingShipment) {
        try {
            shipmentProviderClient.registerShipment(shipment.providerDefinition)
            eventPublisher.publishEvent(ShipmentEvents.SHIPMENT_CREATED, shipment.toEventDTO())
        } catch (e: Exception) {
            shipmentService.discardShipment(shipment, e.message ?: "Unknown reason")
        }
    }

}
