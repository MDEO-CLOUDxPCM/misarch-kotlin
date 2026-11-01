package org.misarch.simulation.model

/**
 * Message published to a registration queue
 *
 * @property pattern message pattern (`register-payment` / `register-shipment`)
 * @property data the registration request as received
 */
data class RegistrationMessage(
    val pattern: String,
    val data: Any,
)
