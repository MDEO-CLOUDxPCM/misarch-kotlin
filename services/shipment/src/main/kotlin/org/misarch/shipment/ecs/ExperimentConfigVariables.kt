package org.misarch.shipment.ecs

import org.misarch.common.ecs.EcsVariable
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * The experiment-config-sidecar variables of the shipment service (served by libs/common at `GET /ecs/defined-variables`
 * and `POST /ecs/variables`). A value that is not an integer >= 0 fails the request with 500; an unknown variable too
 * (`Unknown variable: <name>`).
 */
@Configuration(proxyBeanMethods = false)
class ExperimentConfigVariables {

    @Bean
    fun providerRetriesVariable(experimentConfigService: ExperimentConfigService): EcsVariable =
        experimentConfigService.providerRetriesVariable

    @Bean
    fun providerRetryDelayVariable(experimentConfigService: ExperimentConfigService): EcsVariable =
        experimentConfigService.providerRetryDelayVariable
}
