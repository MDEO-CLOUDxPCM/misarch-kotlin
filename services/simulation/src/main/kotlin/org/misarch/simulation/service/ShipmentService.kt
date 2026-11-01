package org.misarch.simulation.service

import org.misarch.simulation.amqp.RabbitPublisher
import org.misarch.simulation.model.RegisterShipmentDTO
import org.misarch.simulation.model.Shipment
import org.misarch.simulation.model.SimulationQueue
import org.misarch.simulation.model.UpdateShipmentDTO
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Service for registered shipments. A shipment stays registered until its queue message is consumed; the provider
 * stub keeps its registrations in memory only (they are lost on a restart).
 *
 * @property publisher publishes registrations to the shipments queue
 * @property callbackService sends manual status updates
 */
@Service
class ShipmentService(
    private val publisher: RabbitPublisher,
    private val callbackService: CallbackService,
) {
    private val logger = LoggerFactory.getLogger(ShipmentService::class.java)

    /** Registered shipments by id; a second registration of an id keeps the first one */
    private val shipments = ConcurrentHashMap<UUID, Shipment>()

    /**
     * Registers the shipment and publishes the whole request to the shipments queue.
     */
    fun register(input: RegisterShipmentDTO) {
        logger.info("Registering shipment: {}", input.shipmentId)
        shipments.putIfAbsent(input.shipmentId, Shipment(input.shipmentId))
        publisher.publish(SimulationQueue.SHIPMENTS, input)
    }

    /**
     * Blocks the registration for automatic processing and sends the given status right away.
     *
     * @throws ResponseStatusException 404 if the shipment is not (or no longer) registered
     */
    fun update(input: UpdateShipmentDTO) {
        logger.info("Manually updating shipment: {} -> {}", input.shipmentId, input.status)
        shipments.computeIfPresent(input.shipmentId) { _, shipment -> shipment.copy(blocked = true) }
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Shipment not found").also {
                logger.error("Shipment not found: {}", input.shipmentId)
            }
        callbackService.sendShipmentStatus(input.shipmentId.toString(), input.status)
    }

    /**
     * Shipments whose queue message has not been consumed yet, in no particular order.
     */
    fun findAll(): List<Shipment> = shipments.values.toList()

    /**
     * Ends the registration of a consumed queue message.
     *
     * @return true if a manual update blocked the automatic callback; false for an unknown shipment (e.g. registered
     *   before a restart), which is processed normally
     */
    fun complete(id: UUID): Boolean {
        val shipment = shipments.remove(id)
        if (shipment == null) logger.error("Shipment not found: {}", id)
        return shipment?.blocked ?: false
    }
}
