package org.misarch.simulation.processing

import java.util.UUID

/**
 * A registered payment or shipment, kept in memory until its queue message is consumed
 *
 * @property id id of the payment or shipment
 * @property blocked true after a manual update: the queue message gets no automatic callback
 */
interface Registration {
    val id: UUID
    var blocked: Boolean
}
