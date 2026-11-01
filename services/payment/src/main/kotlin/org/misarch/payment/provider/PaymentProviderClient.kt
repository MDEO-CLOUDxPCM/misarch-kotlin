package org.misarch.payment.provider

import kotlinx.coroutines.reactor.awaitSingle
import org.misarch.payment.graphql.model.PaymentMethod
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import java.util.UUID

/**
 * Registers payments at the external payment provider (`POST <SIMULATION_URL>/payment/register`).
 *
 * @property properties the provider configuration
 */
@Component
class PaymentProviderClient(
    private val properties: PaymentProviderConfigurationProperties,
    webClientBuilder: WebClient.Builder,
) {

    private val webClient = webClientBuilder.build()

    /**
     * Registers a payment; a failure (network error, non-2xx answer) propagates.
     *
     * @param paymentId the payment
     * @param amount the amount to pay
     * @param paymentMethod the payment method
     */
    suspend fun registerPayment(paymentId: UUID, amount: Long, paymentMethod: PaymentMethod) {
        val paymentType = when (paymentMethod) {
            PaymentMethod.CREDIT_CARD -> "credit-card"
            PaymentMethod.PREPAYMENT -> "prepayment"
            PaymentMethod.INVOICE -> "invoice"
        }
        webClient.post().uri("${properties.simulationUrl.toString().trimEnd('/')}/payment/register")
            .bodyValue(PaymentProviderPaymentDefinition(paymentId, amount, paymentType))
            .retrieve()
            .toBodilessEntity()
            .awaitSingle()
    }

}
