package org.misarch.shipment.persistence.repository

import org.misarch.shipment.persistence.model.ShipmentMethodEntity
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository
import java.util.UUID

/**
 * Repository for [ShipmentMethodEntity]s
 */
@Repository
interface ShipmentMethodRepository : CoroutineCrudRepository<ShipmentMethodEntity, UUID>
