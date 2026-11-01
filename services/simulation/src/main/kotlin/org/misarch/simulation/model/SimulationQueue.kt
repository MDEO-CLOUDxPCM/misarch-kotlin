package org.misarch.simulation.model

/** Name of the queue of the payment registrations */
const val PAYMENTS_QUEUE = "payments-queue"

/** Name of the queue of the shipment registrations */
const val SHIPMENTS_QUEUE = "shipments-queue"

/**
 * The two simulated providers, each with the RabbitMQ queue of its registrations
 *
 * @property queueName name of the (durable) queue
 * @property idField field of a queued registration holding its id
 * @property successStatus status of a successful registration in the callback
 */
enum class SimulationQueue(
    val queueName: String,
    val idField: String,
    val successStatus: String,
) {
    PAYMENTS(PAYMENTS_QUEUE, "paymentId", "SUCCEEDED"),
    SHIPMENTS(SHIPMENTS_QUEUE, "shipmentId", "DELIVERED");

    /** Status of a failed registration in the callback */
    val failureStatus: String get() = "FAILED"
}
