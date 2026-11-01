package org.misarch.simulation.service

import org.misarch.simulation.model.PendingRegistrationDTO
import org.misarch.simulation.model.Registration
import org.misarch.simulation.model.SimulationQueue
import org.misarch.simulation.model.StatusUpdate
import org.slf4j.LoggerFactory
import org.springframework.amqp.core.Message
import org.springframework.amqp.core.MessageDeliveryMode
import org.springframework.amqp.core.MessageProperties
import org.springframework.amqp.rabbit.core.RabbitTemplate
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import tools.jackson.databind.json.JsonMapper
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The registrations of one simulated provider (one bean per [SimulationQueue]). A registration stays pending until
 * its queue message is consumed; the provider keeps them in memory only (they are lost on a restart).
 *
 * @property queue the provider's queue
 */
class RegistrationService<R : Registration>(
    val queue: SimulationQueue,
    private val rabbitTemplate: RabbitTemplate,
    private val jsonMapper: JsonMapper,
    private val callbackService: CallbackService,
) {

    private val logger = LoggerFactory.getLogger(RegistrationService::class.java)

    /** Pending registrations by id; a second registration of an id keeps the first one */
    private val registrations = ConcurrentHashMap<UUID, PendingRegistrationDTO<R>>()

    /**
     * Registers [registration] and publishes it, as received, to the provider's queue (transient message).
     */
    fun register(registration: R) {
        logger.info("[{}] Registering {}", queue.queueName, registration.id)
        registrations.putIfAbsent(registration.id, PendingRegistrationDTO(registration))
        val properties = MessageProperties().apply {
            contentType = MessageProperties.CONTENT_TYPE_JSON
            deliveryMode = MessageDeliveryMode.NON_PERSISTENT
        }
        rabbitTemplate.send(queue.queueName, Message(jsonMapper.writeValueAsBytes(registration), properties))
    }

    /**
     * Blocks the registration for automatic processing and sends the given status right away.
     *
     * @throws ResponseStatusException 404 if the registration is not (or no longer) pending
     */
    fun update(update: StatusUpdate) {
        logger.info("[{}] Manually updating {} -> {}", queue.queueName, update.id, update.status)
        registrations.computeIfPresent(update.id) { _, pending -> pending.copy(blocked = true) }
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Registration not found").also {
                logger.error("[{}] Registration not found: {}", queue.queueName, update.id)
            }
        callbackService.sendStatus(queue, update.id.toString(), update.status)
    }

    /**
     * The pending registrations, in no particular order.
     */
    fun findAll(): List<PendingRegistrationDTO<R>> = registrations.values.toList()

    /**
     * Ends the registration of a consumed queue message.
     *
     * @return true if a manual update blocked the automatic callback; false for an unknown registration (e.g. one
     *   made before a restart), which is processed normally
     */
    fun complete(id: UUID): Boolean {
        val pending = registrations.remove(id)
        if (pending == null) {
            logger.error("[{}] Registration not found: {}", queue.queueName, id)
        }
        return pending?.blocked ?: false
    }

}
