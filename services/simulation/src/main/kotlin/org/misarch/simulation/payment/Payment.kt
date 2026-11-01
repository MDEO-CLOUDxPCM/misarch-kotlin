package org.misarch.simulation.payment

import org.misarch.simulation.processing.Registration
import java.util.UUID

/**
 * A registered payment
 *
 * @property id id of the payment
 * @property paymentType type of the payment
 * @property blocked true after a manual update
 */
class Payment(
    override val id: UUID,
    val paymentType: String,
    override var blocked: Boolean = false,
) : Registration
