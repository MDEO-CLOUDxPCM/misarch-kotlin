package org.misarch.payment.provider

import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.misarch.payment.graphql.model.PaymentMethod
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import java.util.UUID

/**
 * Registers payments at the external payment provider (`POST <SIMULATION_URL>/payment/register`)
 *
 * @property properties the provider configuration
 */
@Component
class PaymentProviderClient(
    private val properties: PaymentProviderConfigurationProperties
) {

    private val logger = LoggerFactory.getLogger(PaymentProviderClient::class.java)

    private val webClient = WebClient.create()

    /**
     * Registers a payment; a failure (network error, non-2xx answer) propagates. Without a configured provider
     * nothing is sent.
     *
     * @param paymentId the payment
     * @param amount the amount to pay
     * @param paymentMethod the payment method
     */
    suspend fun registerPayment(paymentId: UUID, amount: Double, paymentMethod: PaymentMethod) {
        if (properties.simulationUrl.isBlank()) {
            logger.error("Simulation URL not set, payment {} is not registered", paymentId)
            return
        }
        val paymentType = when (paymentMethod) {
            PaymentMethod.CREDIT_CARD -> "credit-card"
            PaymentMethod.PREPAYMENT -> "prepayment"
            PaymentMethod.INVOICE -> "invoice"
        }
        webClient.post().uri("${properties.simulationUrl.trimEnd('/')}/payment/register")
            .bodyValue(PaymentProviderPaymentDefinition(paymentId, amount, paymentType))
            .retrieve()
            .toBodilessEntity()
            .awaitSingleOrNull()
    }

}
