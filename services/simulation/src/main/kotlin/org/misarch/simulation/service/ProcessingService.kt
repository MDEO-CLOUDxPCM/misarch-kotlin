package org.misarch.simulation.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.misarch.simulation.ecs.ExperimentConfigService
import org.misarch.simulation.model.SimulationQueue
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import tools.jackson.core.JacksonException
import tools.jackson.databind.json.JsonMapper
import java.util.EnumMap
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

/**
 * Processes the messages of `payments-queue` and `shipments-queue`: per-minute rate limit, processing time, random
 * outcome with the success rate, status callback to payment or shipment. A message consumed while the minute window
 * is reset counts toward either window.
 */
@Service
class ProcessingService(
    private val experimentConfigService: ExperimentConfigService,
    registrationServices: List<RegistrationService<*>>,
    private val callbackService: CallbackService,
    private val jsonMapper: JsonMapper,
    private val scope: CoroutineScope,
    private val random: Random,
) {
    private val logger = LoggerFactory.getLogger(ProcessingService::class.java)

    private val registrationServices = registrationServices.associateBy { it.queue }

    /** Messages processed in the current minute, per queue */
    private val messageCounts = EnumMap<SimulationQueue, AtomicInteger>(SimulationQueue::class.java).apply {
        SimulationQueue.entries.forEach { put(it, AtomicInteger()) }
    }

    /** Queues that reached their limit in the current minute and hold their further messages */
    private val paused = EnumMap<SimulationQueue, AtomicBoolean>(SimulationQueue::class.java).apply {
        SimulationQueue.entries.forEach { put(it, AtomicBoolean()) }
    }

    /**
     * Consumes one message. Within the rate limit, the message is processed: the callback follows after the
     * processing time, unless a manual update blocked the registration. A message without the queue's id is dropped.
     *
     * @return true if the message is to be acknowledged; false when the rate limit is reached: the message stays
     *   unacknowledged until the consumer of the queue restarts at the next minute
     */
    fun consume(queue: SimulationQueue, body: ByteArray): Boolean {
        val count = messageCounts.getValue(queue).get()
        val maxMessages = experimentConfigService.messagesPerMinute(queue)
        if (count >= maxMessages) {
            logger.debug("[{}] Maximum message count reached, holding the messages until the reset", queue.queueName)
            paused.getValue(queue).set(true)
            return false
        }
        val id = registrationId(queue, body) ?: return true
        logger.debug("[{}] Processing {} [{}/{}]", queue.queueName, id, count + 1, maxMessages)
        if (isBlocked(queue, id)) return true

        messageCounts.getValue(queue).incrementAndGet()
        val processingTime = maxOf(experimentConfigService.processingTime(queue), 0).seconds
        logger.info("[{}] Sending the status update of {} in {}", queue.queueName, id, processingTime)
        scope.launch {
            delay(processingTime)
            sendStatus(queue, id)
        }
        return true
    }

    /**
     * Starts a new minute window: resets the counts of both queues.
     *
     * @return the paused queues that may process messages again (their limit is > 0): their consumers restart so
     *   that the broker redelivers the held messages
     */
    fun resetMessageCounts(): List<SimulationQueue> {
        logger.debug("Resetting message counts")
        return SimulationQueue.entries.filter { queue ->
            messageCounts.getValue(queue).set(0)
            paused.getValue(queue).getAndSet(false) && experimentConfigService.messagesPerMinute(queue) > 0
        }
    }

    /** The non-empty string id of the queue's kind (`paymentId` / `shipmentId`), or null (logged). */
    private fun registrationId(queue: SimulationQueue, body: ByteArray): String? {
        val message = try {
            jsonMapper.readTree(body)
        } catch (e: JacksonException) {
            logger.error("[{}] Dropping unparseable message: {}", queue.queueName, e.originalMessage)
            return null
        }
        val id = message?.takeIf { it.isObject }?.get(queue.idField)?.takeIf { it.isString }?.stringValue()?.takeIf { it.isNotEmpty() }
        if (id == null) {
            logger.error("[{}] Dropping message without {}: {}", queue.queueName, queue.idField, String(body).take(200))
        }
        return id
    }

    /** Ends the registration of the message; true if a manual update blocked it (an id that is no UUID is unknown). */
    private fun isBlocked(queue: SimulationQueue, id: String): Boolean {
        val uuid = runCatching { UUID.fromString(id) }.getOrNull() ?: return false
        return registrationServices.getValue(queue).complete(uuid)
    }

    /** Draws the outcome with the success rate of this moment and sends the callback. */
    private fun sendStatus(queue: SimulationQueue, id: String) {
        val successful = random.nextDouble() < experimentConfigService.successRate(queue)
        callbackService.sendStatus(queue, id, if (successful) queue.successStatus else queue.failureStatus)
    }
}
