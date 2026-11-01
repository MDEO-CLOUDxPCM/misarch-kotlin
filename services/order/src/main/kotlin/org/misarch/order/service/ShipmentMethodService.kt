package org.misarch.order.service

import org.misarch.order.event.model.ShipmentMethodDTO
import org.misarch.order.persistence.model.ShipmentMethodEntity
import org.misarch.order.persistence.repository.ShipmentMethodRepository
import org.springframework.stereotype.Service

/**
 * Service for [ShipmentMethodEntity]s
 */
@Service
class ShipmentMethodService(
    private val repository: ShipmentMethodRepository
) {

    /**
     * Registers a shipment method
     *
     * @param shipmentMethodDTO the created shipment method
     */
    suspend fun registerShipmentMethod(shipmentMethodDTO: ShipmentMethodDTO) {
        repository.createShipmentMethod(shipmentMethodDTO.id)
    }

}
