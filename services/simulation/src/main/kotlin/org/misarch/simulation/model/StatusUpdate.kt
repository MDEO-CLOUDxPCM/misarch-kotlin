package org.misarch.simulation.model

import java.util.UUID

/**
 * A manual status update of a registration
 */
interface StatusUpdate {

    /** The id of the registered payment or shipment */
    val id: UUID

    /** The status sent to the payment or shipment service */
    val status: String
}
