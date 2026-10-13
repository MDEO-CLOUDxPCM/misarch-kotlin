package org.misarch.shipment.ecs

import org.misarch.common.ecs.EcsVariable
import org.misarch.common.ecs.VariableDefinition
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Service

/**
 * Service for handling experiment configuration variables
 */
@Service
class ExperimentConfigService {

    companion object {
        /**
         * The name of the provider retries variable
         */
        const val PROVIDER_RETRIES_NAME = "providerRetries"

        /**
         * The definition of the provider retries variable
         */
        val PROVIDER_RETRIES_DEFINITION = VariableDefinition(
            mapOf("\$schema" to "http://json-schema.org/draft-07/schema#", "type" to "integer", "minimum" to 0), 3
        )

        /**
         * The name of the provider retry delay variable
         */
        const val PROVIDER_RETRY_DELAY_NAME = "providerRetryDelay"

        /**
         * The definition of the provider retry delay variable
         */
        val PROVIDER_RETRY_DELAY_DEFINITION = VariableDefinition(
            mapOf("\$schema" to "http://json-schema.org/draft-07/schema#", "type" to "integer", "minimum" to 0), 500
        )
    }

    /**
     * The number of retries for the provider
     * Does NOT include the initial request
     */
    var providerRetries: Int = 3

    /**
     * The delay between retries for the provider
     */
    var providerRetryDelay: Int = 500

}

/**
 * The experiment-config-sidecar variables of the shipment service (served by libs/common at `GET /ecs/defined-variables`
 * and `POST /ecs/variables`). Values are cast with `as Int` like in the original: a non-Int JSON value (decimal,
 * number > Int.MAX_VALUE) fails the request with 500; an unknown variable too (`Unknown variable: <name>`).
 */
@Configuration(proxyBeanMethods = false)
class ExperimentConfigVariables {

    @Bean
    fun providerRetriesVariable(experimentConfigService: ExperimentConfigService): EcsVariable = object : EcsVariable {
        override val name = ExperimentConfigService.PROVIDER_RETRIES_NAME
        override val definition = ExperimentConfigService.PROVIDER_RETRIES_DEFINITION
        override fun set(value: Any) {
            experimentConfigService.providerRetries = value as Int
        }
    }

    @Bean
    fun providerRetryDelayVariable(experimentConfigService: ExperimentConfigService): EcsVariable = object : EcsVariable {
        override val name = ExperimentConfigService.PROVIDER_RETRY_DELAY_NAME
        override val definition = ExperimentConfigService.PROVIDER_RETRY_DELAY_DEFINITION
        override fun set(value: Any) {
            experimentConfigService.providerRetryDelay = value as Int
        }
    }
}
