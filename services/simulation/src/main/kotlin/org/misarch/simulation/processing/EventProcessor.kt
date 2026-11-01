package org.misarch.simulation.processing

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.misarch.simulation.connector.ConnectorService
import org.misarch.simulation.ecs.ExperimentConfigService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.util.UUID
import java.util.concurrent.ThreadLocalRandom

/**
 * Consumes `payments-queue` / `shipments-queue`: applies the per-minute rate limit, acks, waits the processing time,
 * draws success or failure and calls payment or shipment back (docs/specs/simulation.md §4).
 *
 * Connects to the broker after all beans exist but before the web server starts, so the HTTP port only opens once
 * RabbitMQ is reachable.
 */
@Service
class EventProcessor(
    private val state: SimulationState,
    private val experimentConfigService: ExperimentConfigService,
    private val connector: ConnectorService,
    private val consumer: QueueConsumer,
    private val fatal: FatalErrorHandler,
    private val jsonMapper: JsonMapper,
    private val scope: CoroutineScope,
    /** Uniform random number in [0, 1) */
    private val random: () -> Double = { ThreadLocalRandom.current().nextDouble() },
) : DeliverySink, SmartInitializingSingleton, DisposableBean {

    private val log = LoggerFactory.getLogger(EventProcessor::class.java)

    /** Connects to RabbitMQ (retrying every 5 s) and starts the consumers. */
    override fun afterSingletonsInstantiated() = consumer.start(this)

    override fun destroy() = consumer.shutdown()

    override fun onDelivery(queue: SimulationQueue, message: IncomingMessage?) {
        state.locked {
            // a delivery on a closing (or replaced) channel is ignored; the broker requeues it
            if (message != null && message.isStale) return@locked
            consumeMessage(queue, message)
        }
    }

    /** Runs under [SimulationState.lock]. */
    private fun SimulationState.consumeMessage(queue: SimulationQueue, message: IncomingMessage?) {
        val maxMessages = experimentConfigService.messagesPerMinute(queue)
        val delaySeconds = experimentConfigService.processingTime(queue)
        val count = messageCounts.getValue(queue)
        if (count >= maxMessages) {
            log.debug("[{}] Maximum message count reached. Pausing processing until reset.", queue.queueName)
            // not acknowledged: the message stays unacked until the channel is closed at the next minute
            processingAllowed[queue] = false
            return
        }
        if (message == null) return
        val parsed = try {
            jsonMapper.readTree(message.body)
        } catch (e: JacksonException) {
            // fix S1 (docs/fixes/simulation.md): a poison message is acked and dropped
            dropPoisonMessage(queue, message, "unparseable message: ${e.originalMessage}")
            return
        }
        val data: JsonNode? = parsed?.takeIf { it.isObject }?.get("data")
        // fix S2: the id is the one of the queue's kind (`paymentId` / `shipmentId`), required before processing
        val id: String? = data?.takeIf { it.isObject }?.get(queue.idField)
            ?.takeIf { it.isString }?.stringValue()?.takeIf { it.isNotEmpty() }
        if (data == null || id == null) {
            dropPoisonMessage(queue, message, "message without ${queue.idField}: ${String(message.body).take(200)}")
            return
        }
        log.debug("[{}] Processing message: {} [{}/{}]", queue.queueName, data, count + 1, maxMessages)
        message.ack()

        // check if message was already manually updated
        if (isBlocked(queue, id)) return

        messageCounts[queue] = count + 1
        log.info("[{}] Sending Event Update after [{}]s", queue.queueName, delaySeconds)
        val delayMillis = maxOf(delaySeconds, 0) * 1000L
        scope.launch {
            delay(delayMillis)
            buildEventUpdate(queue, id)
        }
    }

    /** Acks a message that cannot be processed and drops it (not counted, no callback). */
    private fun dropPoisonMessage(queue: SimulationQueue, message: IncomingMessage, reason: String) {
        log.error("[{}] Dropping {}", queue.queueName, reason)
        message.ack()
    }

    /** Looks the registration up, removes it and returns its `blocked` flag (not found: not blocked). */
    private fun SimulationState.isBlocked(queue: SimulationQueue, id: String): Boolean {
        val uuid = runCatching { UUID.fromString(id) }.getOrNull()
        val repository = repository(queue)
        if (queue == SimulationQueue.SHIPMENTS) log.info("Checking if shipment is blocked: {}", id)
        val registration = repository.findById(uuid)
        if (registration == null) {
            // can happen if simulation service was restarted
            log.error("{} not found: {}", if (queue == SimulationQueue.PAYMENTS) "Payment" else "Shipment", id)
            return false
        }
        // remove the registration from memory since it will be processed
        repository.delete(uuid)
        return registration.blocked
    }

    /** Draws the outcome with the success rate read now and sends the callback. */
    private fun buildEventUpdate(queue: SimulationQueue, id: String) {
        val successful = random() < experimentConfigService.successRate(queue)
        when (queue) {
            SimulationQueue.PAYMENTS -> connector.sendUpdateToPayment(id, if (successful) "SUCCEEDED" else "FAILED")
            SimulationQueue.SHIPMENTS -> connector.sendUpdateToShipment(id, if (successful) "DELIVERED" else "FAILED")
        }
    }

    /**
     * Rate-limit reset at second 0 of every minute: resets all counts; if a queue was paused and its limit is > 0,
     * closes and reopens the broker connection so the held (unacked) messages are redelivered.
     */
    @Scheduled(cron = "\${simulation.reset-cron:0 * * * * *}")
    fun resetMessageCounts() {
        val reconnect = state.locked {
            log.debug("Resetting message counts.")
            var reconnect = false
            SimulationQueue.entries.forEach { queue ->
                if (processingAllowed.getValue(queue) == false && experimentConfigService.messagesPerMinute(queue) > 0) {
                    reconnect = true
                }
                messageCounts[queue] = 0
                processingAllowed[queue] = true
            }
            // nothing more is consumed from the current channel
            if (reconnect) consumer.markClosing()
            reconnect
        }
        if (reconnect) {
            try {
                log.debug("Reconnecting to RabbitMQ...")
                consumer.reconnect()
            } catch (e: Exception) {
                fatal.fatal("Failed to reconnect to RabbitMQ", e)
            }
        }
    }
}
