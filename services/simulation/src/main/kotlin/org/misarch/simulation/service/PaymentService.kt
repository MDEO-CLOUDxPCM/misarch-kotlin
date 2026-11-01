package org.misarch.simulation.service

import org.misarch.simulation.amqp.RabbitPublisher
import org.misarch.simulation.model.Payment
import org.misarch.simulation.model.RegisterPaymentDTO
import org.misarch.simulation.model.SimulationQueue
import org.misarch.simulation.model.UpdatePaymentDTO
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Service for registered payments. A payment stays registered until its queue message is consumed; the provider
 * stub keeps its registrations in memory only (they are lost on a restart).
 */
@Service
class PaymentService(
    private val publisher: RabbitPublisher,
    private val callbackService: CallbackService,
) {
    private val logger = LoggerFactory.getLogger(PaymentService::class.java)

    /** Registered payments by id; a second registration of an id keeps the first one */
    private val payments = ConcurrentHashMap<UUID, Payment>()

    /**
     * Registers the payment and publishes the whole request to the payments queue.
     */
    fun register(input: RegisterPaymentDTO) {
        logger.info("Registering payment: {}", input.paymentId)
        payments.putIfAbsent(input.paymentId, Payment(input.paymentId, input.paymentType))
        publisher.publish(SimulationQueue.PAYMENTS, input)
    }

    /**
     * Blocks the registration for automatic processing and sends the given status right away.
     *
     * @throws ResponseStatusException 404 if the payment is not (or no longer) registered
     */
    fun update(input: UpdatePaymentDTO) {
        logger.info("Manually updating payment: {} -> {}", input.paymentId, input.status)
        payments.computeIfPresent(input.paymentId) { _, payment -> payment.copy(blocked = true) }
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Payment not found").also {
                logger.error("Payment not found: {}", input.paymentId)
            }
        callbackService.sendPaymentStatus(input.paymentId.toString(), input.status)
    }

    /**
     * Payments whose queue message has not been consumed yet, in no particular order.
     */
    fun findAll(): List<Payment> = payments.values.toList()

    /**
     * Ends the registration of a consumed queue message.
     *
     * @return true if a manual update blocked the automatic callback; false for an unknown payment (e.g. registered
     *   before a restart), which is processed normally
     */
    fun complete(id: UUID): Boolean {
        val payment = payments.remove(id)
        if (payment == null) logger.error("Payment not found: {}", id)
        return payment?.blocked ?: false
    }
}
