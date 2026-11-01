package org.misarch.simulation.ecs

import org.misarch.common.ecs.EcsVariable
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * The experiment-config-sidecar variables of the simulation (served by libs/common at `GET /ecs/defined-variables`
 * and `POST /ecs/variables`, in the order declared here). A value that does not match the variable's type, or an
 * unknown variable, fails the request with 500; the variables before it in the body stay applied.
 */
@Configuration(proxyBeanMethods = false)
class ExperimentConfigVariables {

    @Bean
    fun paymentsPerMinuteVariable(experimentConfigService: ExperimentConfigService): EcsVariable =
        experimentConfigService.paymentsPerMinute

    @Bean
    fun shipmentsPerMinuteVariable(experimentConfigService: ExperimentConfigService): EcsVariable =
        experimentConfigService.shipmentsPerMinute

    @Bean
    fun paymentProcessingTimeVariable(experimentConfigService: ExperimentConfigService): EcsVariable =
        experimentConfigService.paymentProcessingTime

    @Bean
    fun shipmentProcessingTimeVariable(experimentConfigService: ExperimentConfigService): EcsVariable =
        experimentConfigService.shipmentProcessingTime

    @Bean
    fun paymentSuccessRateVariable(experimentConfigService: ExperimentConfigService): EcsVariable =
        experimentConfigService.paymentSuccessRate

    @Bean
    fun shipmentSuccessRateVariable(experimentConfigService: ExperimentConfigService): EcsVariable =
        experimentConfigService.shipmentSuccessRate
}
