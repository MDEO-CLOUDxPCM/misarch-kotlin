package org.misarch.payment.service

import org.misarch.common.event.EventPublisher
import org.misarch.common.persistence.requiredId
import org.misarch.payment.client.PaymentProviderClient
import org.misarch.payment.event.PaymentEvents
import org.misarch.payment.event.model.OrderDTO
import org.misarch.payment.event.model.PaymentEventDTO
import org.misarch.payment.graphql.model.PaymentMethod
import org.misarch.payment.graphql.model.PaymentStatus
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Creation of payments and their registration at the payment provider. The payments are stored by
 * [PaymentService]'s transactions; the provider calls are not part of a transaction (a database connection would
 * otherwise be held while they run). A payment whose registration fails is removed again and the failure propagates,
 * so that the event is redelivered.
 */
@Service
class PaymentRegistrationService(
    private val paymentService: PaymentService,
    private val paymentProviderClient: PaymentProviderClient,
    private val eventPublisher: EventPublisher
) {

    /**
     * Creates the payment of a validated order and registers it at the provider; payments that are not prepaid
     * enable the order right away (`payment-enabled`)
     *
     * @param order the order, stored and relayed in the payment events
     */
    suspend fun createPaymentForOrder(order: OrderDTO) {
        val payment = paymentService.createPayment(order) ?: return
        try {
            paymentProviderClient.registerPayment(payment.payment.requiredId, payment.payment.totalAmount, payment.paymentMethod)
            if (payment.paymentMethod != PaymentMethod.PREPAYMENT) {
                eventPublisher.publishEvent(PaymentEvents.PAYMENT_ENABLED, PaymentEventDTO(payment.order))
            }
        } catch (e: Exception) {
            paymentService.discardPayment(payment.payment.requiredId)
            throw e
        }
    }

    /**
     * Applies a status reported by the payment provider ([PaymentService.updatePaymentStatus]) and registers a
     * retried credit card payment again; a failing registration propagates (the provider repeats its callback)
     *
     * @param id the payment
     * @param status the reported status
     */
    suspend fun updatePaymentStatus(id: UUID, status: PaymentStatus) {
        val retried = paymentService.updatePaymentStatus(id, status) ?: return
        paymentProviderClient.registerPayment(retried.requiredId, retried.totalAmount, PaymentMethod.CREDIT_CARD)
    }

}
