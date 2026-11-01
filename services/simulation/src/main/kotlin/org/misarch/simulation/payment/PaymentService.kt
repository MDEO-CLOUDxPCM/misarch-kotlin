package org.misarch.simulation.payment

import org.misarch.simulation.connector.ConnectorService
import org.misarch.simulation.payment.model.PaymentDTO
import org.misarch.simulation.payment.model.RegisterPaymentDTO
import org.misarch.simulation.payment.model.UpdatePaymentDTO
import org.misarch.simulation.processing.RegistrationPublisher
import org.misarch.simulation.processing.SimulationQueue
import org.misarch.simulation.processing.SimulationState
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException

/**
 * Service for registered payments
 *
 * @property state the in-memory registrations
 * @property publisher publishes registrations to the payments queue
 * @property connector sends manual status updates
 */
@Service
class PaymentService(
    private val state: SimulationState,
    private val publisher: RegistrationPublisher,
    private val connector: ConnectorService,
) {
    private val logger = LoggerFactory.getLogger(PaymentService::class.java)

    /**
     * Stores the payment and publishes the whole request to the payments queue.
     */
    fun register(input: RegisterPaymentDTO) {
        logger.info("Registering payment")
        state.locked { payments.create(Payment(input.paymentId, input.paymentType)) }
        publisher.emit(SimulationQueue.PAYMENTS, input)
    }

    /**
     * Blocks the (first) registration for automatic processing and sends the given status right away.
     *
     * @throws ResponseStatusException 404 if the payment is not (or no longer) registered
     */
    fun update(input: UpdatePaymentDTO) {
        logger.info("Manually updating payment: {} -> {}", input.paymentId, input.status)
        val payment = state.locked { payments.findById(input.paymentId)?.also { it.blocked = true } }
        if (payment == null) {
            logger.error("Payment not found: {}", input.paymentId)
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Payment not found")
        }
        connector.sendUpdateToPayment(input.paymentId.toString(), input.status)
    }

    /**
     * Payments whose queue message has not been consumed yet.
     */
    fun findAll(): List<PaymentDTO> =
        state.locked { payments.findAll().map { PaymentDTO(it.id, it.paymentType, it.blocked) } }
}
