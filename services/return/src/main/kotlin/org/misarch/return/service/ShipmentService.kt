package org.misarch.`return`.service

import org.misarch.`return`.event.model.ShipmentDTO
import org.misarch.`return`.event.model.ShipmentStatus
import org.misarch.`return`.event.model.ShipmentStatusUpdatedDTO
import org.misarch.`return`.persistence.model.ShipmentEntity
import org.misarch.`return`.persistence.repository.OrderItemRepository
import org.misarch.`return`.persistence.repository.ShipmentRepository
import org.springframework.stereotype.Service
import java.time.OffsetDateTime

/**
 * Service for [ShipmentEntity]s
 *
 * @param repository the provided repository
 * @param orderItemRepository the repository for order items
 */
@Service
class ShipmentService(
    private val repository: ShipmentRepository,
    private val orderItemRepository: OrderItemRepository
) {

    /**
     * Registers a shipment, also registers the order items that belong to the shipment
     * Ignores the shipment if the order id is null (belongs to a return).
     * Not transactional: if an item upsert fails (e.g. order not yet replicated), the shipment row stays and every
     * redelivery fails on the duplicate shipment id, like in the original.
     *
     * @param shipmentDTO the shipment to register
     */
    suspend fun registerShipment(shipmentDTO: ShipmentDTO) {
        if (shipmentDTO.orderId == null) {
            return
        }
        repository.createShipment(
            shipmentDTO.id,
            if (shipmentDTO.status == ShipmentStatus.DELIVERED) OffsetDateTime.now() else null
        )
        shipmentDTO.orderItemIds.forEach {
            orderItemRepository.upsertOrderItemFromShipment(
                it,
                shipmentDTO.id,
                shipmentDTO.orderId
            )
        }
    }

    /**
     * Updates deliveredAt if the status is DELIVERED (unknown shipments, e.g. return shipments, fail)
     *
     * @param updateDTO contains the new status and the id of the shipment
     */
    suspend fun updateDeliveredAt(updateDTO: ShipmentStatusUpdatedDTO) {
        if (updateDTO.status == ShipmentStatus.DELIVERED) {
            val shipment = repository.findById(updateDTO.id) ?: throw NoSuchElementException()
            shipment.deliveredAt = OffsetDateTime.now()
            repository.save(shipment)
        }
    }

}
