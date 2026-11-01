package org.misarch.simulation.amqp

/**
 * Message published to a registration queue
 *
 * @property pattern message pattern (`register-payment` / `register-shipment`)
 * @property data the registration as received
 */
data class RegistrationMessage(
    val pattern: String,
    val data: Any,
)
