package org.misarch.simulation.model

/**
 * The two RabbitMQ queues of the registrations
 *
 * @property queueName name of the queue
 * @property pattern message pattern of the registrations
 * @property idField field of the message `data` holding the registration id
 */
enum class SimulationQueue(
    val queueName: String,
    val pattern: String,
    val idField: String,
) {
    PAYMENTS("payments-queue", "register-payment", "paymentId"),
    SHIPMENTS("shipments-queue", "register-shipment", "shipmentId"),
}
