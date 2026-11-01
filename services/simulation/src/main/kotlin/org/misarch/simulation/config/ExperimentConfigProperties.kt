package org.misarch.simulation.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Start values of the experiment-config variables, from the env vars of the same name (`PAYMENTS_PER_MINUTE`, ...).
 * `null` (unset or empty) starts with the variable's default; the experiment-config sidecar can change every value
 * at runtime.
 */
@ConfigurationProperties("misarch.simulation.experiment")
data class ExperimentConfigProperties(
    val paymentsPerMinute: Int? = null,
    val shipmentsPerMinute: Int? = null,
    val paymentProcessingTime: Int? = null,
    val shipmentProcessingTime: Int? = null,
    val paymentSuccessRate: Double? = null,
    val shipmentSuccessRate: Double? = null,
)
