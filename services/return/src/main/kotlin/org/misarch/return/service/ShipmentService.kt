package org.misarch.`return`.service

import org.misarch.`return`.event.model.ShipmentDTO
import org.misarch.`return`.event.model.ShipmentStatus
import org.misarch.`return`.event.model.ShipmentStatusUpdatedDTO
import org.misarch.`return`.persistence.model.ShipmentEntity
import org.misarch.`return`.persistence.repository.OrderItemRepository
import org.misarch.`return`.persistence.repository.ShipmentRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime

/**
 * Service for [ShipmentEntity]s
 */
@Service
class ShipmentService(
    private val repository: ShipmentRepository,
    private val orderItemRepository: OrderItemRepository
) {

    /**
     * Registers a shipment, also registers the order items that belong to the shipment
     * Ignores the shipment if the order id is null (belongs to a return).
     * Idempotent and run in the event handler's transaction: if an item upsert fails (e.g. order not yet
     * replicated), the shipment row is rolled back too and the redelivery succeeds once the order is known.
     *
     * @param shipmentDTO the shipment to register
     */
    @Transactional
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
     * Updates deliveredAt if the status is DELIVERED.
     * Order shipments are upserted (the status update may be processed before `shipment/shipment/created`);
     * unknown shipments without order (return shipments, never registered) are ignored, so their status updates are
     * acknowledged instead of being redelivered.
     *
     * @param updateDTO contains the new status and the id of the shipment
     */
    suspend fun updateDeliveredAt(updateDTO: ShipmentStatusUpdatedDTO) {
        if (updateDTO.status == ShipmentStatus.DELIVERED) {
            if (updateDTO.orderId != null) {
                repository.upsertDeliveredAt(updateDTO.id, OffsetDateTime.now())
            } else {
                repository.updateDeliveredAt(updateDTO.id, OffsetDateTime.now())
            }
        }
    }

}
