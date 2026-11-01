package org.misarch.simulation.ecs

import org.misarch.simulation.processing.SimulationQueue
import org.springframework.core.env.Environment
import org.springframework.stereotype.Service

/**
 * Service for handling experiment configuration variables: rate limit, processing time and success rate per queue.
 * An environment variable with the name of a variable replaces its default at start-up (fix S4).
 */
@Service
class ExperimentConfigService(environment: Environment) {

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
    @Volatile
    var paymentsPerMinute: Int = environment.int(PAYMENTS_PER_MINUTE, DEFAULT_MESSAGES_PER_MINUTE)

    /**
     * Maximum number of shipments processed per minute
     */
    @Volatile
    var shipmentsPerMinute: Int = environment.int(SHIPMENTS_PER_MINUTE, DEFAULT_MESSAGES_PER_MINUTE)

    /**
     * Processing time of a payment in seconds
     */
    @Volatile
    var paymentProcessingTime: Int = environment.int(PAYMENT_PROCESSING_TIME, DEFAULT_PROCESSING_TIME)

    /**
     * Processing time of a shipment in seconds
     */
    @Volatile
    var shipmentProcessingTime: Int = environment.int(SHIPMENT_PROCESSING_TIME, DEFAULT_PROCESSING_TIME)

    /**
     * Probability that a payment succeeds
     */
    @Volatile
    var paymentSuccessRate: Double = environment.double(PAYMENT_SUCCESS_RATE, DEFAULT_SUCCESS_RATE)

    /**
     * Probability that a shipment is delivered
     */
    @Volatile
    var shipmentSuccessRate: Double = environment.double(SHIPMENT_SUCCESS_RATE, DEFAULT_SUCCESS_RATE)

    fun messagesPerMinute(queue: SimulationQueue): Int = when (queue) {
        SimulationQueue.PAYMENTS -> paymentsPerMinute
        SimulationQueue.SHIPMENTS -> shipmentsPerMinute
    }

    fun processingTime(queue: SimulationQueue): Int = when (queue) {
        SimulationQueue.PAYMENTS -> paymentProcessingTime
        SimulationQueue.SHIPMENTS -> shipmentProcessingTime
    }

    fun successRate(queue: SimulationQueue): Double = when (queue) {
        SimulationQueue.PAYMENTS -> paymentSuccessRate
        SimulationQueue.SHIPMENTS -> shipmentSuccessRate
    }

    private fun Environment.int(name: String, default: Int): Int =
        getProperty(name, Int::class.javaObjectType) ?: default

    private fun Environment.double(name: String, default: Double): Double =
        getProperty(name, Double::class.javaObjectType) ?: default
}
