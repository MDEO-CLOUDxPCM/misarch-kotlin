package org.misarch.shipment.ecs

import org.misarch.common.ecs.IntegerVariable
import org.springframework.stereotype.Service

/**
 * Service for handling experiment configuration variables (exposed to the sidecar by [ExperimentConfigVariables])
 */
@Service
class ExperimentConfigService {

    companion object {
        /**
         * The name of the provider retries variable
         */
        const val PROVIDER_RETRIES_NAME = "providerRetries"

        /**
         * The name of the provider retry delay variable
         */
        const val PROVIDER_RETRY_DELAY_NAME = "providerRetryDelay"
    }

    /**
     * Variable [providerRetries]
     */
    val providerRetriesVariable = IntegerVariable(PROVIDER_RETRIES_NAME, defaultValue = 3, minimum = 0)

    /**
     * Variable [providerRetryDelay]
     */
    val providerRetryDelayVariable = IntegerVariable(PROVIDER_RETRY_DELAY_NAME, defaultValue = 500, minimum = 0)

    /**
     * The number of retries for the provider
     * Does NOT include the initial request
     */
    val providerRetries: Int get() = providerRetriesVariable.value

    /**
     * The delay between retries for the provider, in milliseconds
     */
    val providerRetryDelay: Int get() = providerRetryDelayVariable.value

}
