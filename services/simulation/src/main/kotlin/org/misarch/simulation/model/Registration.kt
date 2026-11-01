package org.misarch.simulation.model

import java.util.UUID

/**
 * A registration at a simulated provider (payment or shipment), published to the provider's queue as received
 */
interface Registration {

    /** The id of the registered payment or shipment */
    val id: UUID
}
