package org.misarch.simulation.ecs

import org.misarch.common.ecs.IntegerVariable
import org.misarch.common.ecs.NumberVariable
import org.misarch.simulation.config.ExperimentConfigProperties
import org.misarch.simulation.model.SimulationQueue
import org.springframework.stereotype.Service

/**
 * Service for handling experiment configuration variables: rate limit, processing time and success rate per queue
 * (exposed to the sidecar by [ExperimentConfigVariables]). Each value is read when it is used, so a change applies
 * immediately.
 *
 * @param properties start values from the environment
 */
@Service
class ExperimentConfigService(properties: ExperimentConfigProperties) {

    companion object {
        const val PAYMENTS_PER_MINUTE = "PAYMENTS_PER_MINUTE"
        const val SHIPMENTS_PER_MINUTE = "SHIPMENTS_PER_MINUTE"
        const val PAYMENT_PROCESSING_TIME = "PAYMENT_PROCESSING_TIME"
        const val SHIPMENT_PROCESSING_TIME = "SHIPMENT_PROCESSING_TIME"
        const val PAYMENT_SUCCESS_RATE = "PAYMENT_SUCCESS_RATE"
        const val SHIPMENT_SUCCESS_RATE = "SHIPMENT_SUCCESS_RATE"

        /** Default of the `*_PER_MINUTE` variables */
        const val DEFAULT_MESSAGES_PER_MINUTE = 1000000

        /** Default of the `*_PROCESSING_TIME` variables, in seconds */
        const val DEFAULT_PROCESSING_TIME = 5

        /** Default of the `*_SUCCESS_RATE` variables */
        const val DEFAULT_SUCCESS_RATE = 0.95
    }

    /**
     * Maximum number of payments processed per minute
     */
    val paymentsPerMinute =
        IntegerVariable(PAYMENTS_PER_MINUTE, DEFAULT_MESSAGES_PER_MINUTE, properties.paymentsPerMinute)

    /**
     * Maximum number of shipments processed per minute
     */
    val shipmentsPerMinute =
        IntegerVariable(SHIPMENTS_PER_MINUTE, DEFAULT_MESSAGES_PER_MINUTE, properties.shipmentsPerMinute)

    /**
     * Processing time of a payment in seconds
     */
    val paymentProcessingTime =
        IntegerVariable(PAYMENT_PROCESSING_TIME, DEFAULT_PROCESSING_TIME, properties.paymentProcessingTime)

    /**
     * Processing time of a shipment in seconds
     */
    val shipmentProcessingTime =
        IntegerVariable(SHIPMENT_PROCESSING_TIME, DEFAULT_PROCESSING_TIME, properties.shipmentProcessingTime)

    /**
     * Probability that a payment succeeds
     */
    val paymentSuccessRate = NumberVariable(PAYMENT_SUCCESS_RATE, DEFAULT_SUCCESS_RATE, properties.paymentSuccessRate)

    /**
     * Probability that a shipment is delivered
     */
    val shipmentSuccessRate =
        NumberVariable(SHIPMENT_SUCCESS_RATE, DEFAULT_SUCCESS_RATE, properties.shipmentSuccessRate)

    /** Current rate limit of the queue */
    fun messagesPerMinute(queue: SimulationQueue): Int = when (queue) {
        SimulationQueue.PAYMENTS -> paymentsPerMinute.value
        SimulationQueue.SHIPMENTS -> shipmentsPerMinute.value
    }

    /** Current processing time of the queue, in seconds */
    fun processingTime(queue: SimulationQueue): Int = when (queue) {
        SimulationQueue.PAYMENTS -> paymentProcessingTime.value
        SimulationQueue.SHIPMENTS -> shipmentProcessingTime.value
    }

    /** Current success rate of the queue */
    fun successRate(queue: SimulationQueue): Double = when (queue) {
        SimulationQueue.PAYMENTS -> paymentSuccessRate.value
        SimulationQueue.SHIPMENTS -> shipmentSuccessRate.value
    }
}
