package org.misarch.shipment.persistence.repository

import org.misarch.shipment.persistence.model.ShipmentMethodEntity
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.util.UUID

/**
 * Repository for [ShipmentMethodEntity]s
 */
interface ShipmentMethodRepository : CoroutineCrudRepository<ShipmentMethodEntity, UUID>
