package org.misarch.simulation.ecs

import org.misarch.common.ecs.EcsVariable
import org.misarch.simulation.ecs.ExperimentConfigService.Companion.DEFAULT_MESSAGES_PER_MINUTE
import org.misarch.simulation.ecs.ExperimentConfigService.Companion.DEFAULT_PROCESSING_TIME
import org.misarch.simulation.ecs.ExperimentConfigService.Companion.DEFAULT_SUCCESS_RATE
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * The experiment-config-sidecar variables of the simulation (served by libs/common at `GET /ecs/defined-variables`
 * and `POST /ecs/variables`, in the order declared here). An unknown variable fails the request with 500
 * (`Unknown variable: <name>`); the variables before it in the body stay applied.
 */
@Configuration(proxyBeanMethods = false)
class ExperimentConfigVariables {

    @Bean
    fun paymentsPerMinuteVariable(experimentConfigService: ExperimentConfigService): EcsVariable =
        IntegerVariable(ExperimentConfigService.PAYMENTS_PER_MINUTE, DEFAULT_MESSAGES_PER_MINUTE) {
            experimentConfigService.paymentsPerMinute = it
        }

    @Bean
    fun shipmentsPerMinuteVariable(experimentConfigService: ExperimentConfigService): EcsVariable =
        IntegerVariable(ExperimentConfigService.SHIPMENTS_PER_MINUTE, DEFAULT_MESSAGES_PER_MINUTE) {
            experimentConfigService.shipmentsPerMinute = it
        }

    @Bean
    fun paymentProcessingTimeVariable(experimentConfigService: ExperimentConfigService): EcsVariable =
        IntegerVariable(ExperimentConfigService.PAYMENT_PROCESSING_TIME, DEFAULT_PROCESSING_TIME) {
            experimentConfigService.paymentProcessingTime = it
        }

    @Bean
    fun shipmentProcessingTimeVariable(experimentConfigService: ExperimentConfigService): EcsVariable =
        IntegerVariable(ExperimentConfigService.SHIPMENT_PROCESSING_TIME, DEFAULT_PROCESSING_TIME) {
            experimentConfigService.shipmentProcessingTime = it
        }

    @Bean
    fun paymentSuccessRateVariable(experimentConfigService: ExperimentConfigService): EcsVariable =
        NumberVariable(ExperimentConfigService.PAYMENT_SUCCESS_RATE, DEFAULT_SUCCESS_RATE) {
            experimentConfigService.paymentSuccessRate = it
        }

    @Bean
    fun shipmentSuccessRateVariable(experimentConfigService: ExperimentConfigService): EcsVariable =
        NumberVariable(ExperimentConfigService.SHIPMENT_SUCCESS_RATE, DEFAULT_SUCCESS_RATE) {
            experimentConfigService.shipmentSuccessRate = it
        }
}
