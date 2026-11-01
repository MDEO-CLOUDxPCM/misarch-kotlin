package org.misarch.simulation.shipment

import org.misarch.simulation.connector.ConnectorService
import org.misarch.simulation.processing.RegistrationPublisher
import org.misarch.simulation.processing.SimulationQueue
import org.misarch.simulation.processing.SimulationState
import org.misarch.simulation.shipment.dto.RegisterShipmentDTO
import org.misarch.simulation.shipment.dto.ShipmentDTO
import org.misarch.simulation.shipment.dto.UpdateShipmentDTO
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException

/**
 * Service for registered shipments
 *
 * @property state the in-memory registrations
 * @property publisher publishes registrations to the shipments queue
 * @property connector sends manual status updates
 */
@Service
class ShipmentService(
    private val state: SimulationState,
    private val publisher: RegistrationPublisher,
    private val connector: ConnectorService,
) {
    private val log = LoggerFactory.getLogger(ShipmentService::class.java)

    /**
     * Stores the shipment and publishes the whole request to the shipments queue.
     */
    fun register(input: RegisterShipmentDTO) {
        log.info("Registering shipment: {}", input.shipmentId)
        state.locked { shipments.create(Shipment(input.shipmentId)) }
        publisher.emit(SimulationQueue.SHIPMENTS, input)
    }

    /**
     * Blocks the (first) registration for automatic processing and sends the given status right away.
     *
     * @throws ResponseStatusException 404 if the shipment is not (or no longer) registered
     */
    fun update(input: UpdateShipmentDTO) {
        log.info("Manually updating shipment: {} -> {}", input.shipmentId, input.status)
        val shipment = state.locked { shipments.findById(input.shipmentId)?.also { it.blocked = true } }
        if (shipment == null) {
            log.error("Shipment not found: {}", input.shipmentId)
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Shipment not found")
        }
        connector.sendUpdateToShipment(input.shipmentId.toString(), input.status)
    }

    /**
     * Shipments whose queue message has not been consumed yet.
     */
    fun findAll(): List<ShipmentDTO> =
        state.locked { shipments.findAll().map { ShipmentDTO(it.id, it.blocked) } }
}
