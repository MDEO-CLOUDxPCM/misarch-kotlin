package org.misarch.simulation.processing

import org.misarch.simulation.config.SimulationVariables

/**
 * The two RabbitMQ queues of the original (`payments-queue`, `shipments-queue`) with the names of their message
 * pattern, id field and configuration variables.
 */
enum class SimulationQueue(
    val queueName: String,
    /** NestJS `ClientProxy.emit` pattern of the registration message. */
    val pattern: String,
    /** Field of the message `data` used for the "blocked" lookup. */
    val idField: String,
    val perMinuteVariable: String,
    val processingTimeVariable: String,
    val successRateVariable: String,
) {
    PAYMENTS(
        "payments-queue", "register-payment", "paymentId",
        SimulationVariables.PAYMENTS_PER_MINUTE, SimulationVariables.PAYMENT_PROCESSING_TIME,
        SimulationVariables.PAYMENT_SUCCESS_RATE,
    ),
    SHIPMENTS(
        "shipments-queue", "register-shipment", "shipmentId",
        SimulationVariables.SHIPMENTS_PER_MINUTE, SimulationVariables.SHIPMENT_PROCESSING_TIME,
        SimulationVariables.SHIPMENT_SUCCESS_RATE,
    ),
}
