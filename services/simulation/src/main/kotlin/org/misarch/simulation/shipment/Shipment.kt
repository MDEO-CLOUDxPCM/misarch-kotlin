package org.misarch.simulation.shipment

import org.misarch.simulation.processing.Registration
import java.util.UUID

/**
 * A registered shipment
 *
 * @property id id of the shipment
 * @property blocked true after a manual update
 */
class Shipment(
    override val id: UUID,
    override var blocked: Boolean = false,
) : Registration
