package org.misarch.simulation.processing

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.misarch.simulation.config.SimulationVariables
import org.misarch.simulation.connector.ConnectorService
import org.misarch.simulation.js.Js
import org.misarch.simulation.js.JsJson
import org.slf4j.LoggerFactory
import tools.jackson.databind.JsonNode
import java.util.concurrent.ThreadLocalRandom

/**
 * Port of the original `EventProcessorService`: consumes `payments-queue` / `shipments-queue`, applies the
 * per-minute rate limit, waits the processing time, draws success/failure and calls payment/shipment back.
 * Semantics in docs/specs/simulation.md §4; every step below mirrors the original code line by line.
 */
class EventProcessor(
    private val state: SimulationState,
    private val variables: SimulationVariables,
    private val connector: ConnectorService,
    private val consumer: QueueConsumer,
    private val fatal: FatalErrorHandler,
    /** Scope of the processing timers (`setTimeout`); tests pass a virtual-time scope. */
    private val scope: CoroutineScope,
    /** `Math.random()`: uniform in [0, 1). */
    private val random: () -> Double = { ThreadLocalRandom.current().nextDouble() },
) : DeliverySink {

    private val log = LoggerFactory.getLogger(EventProcessor::class.java)

    /** Connects to RabbitMQ (retrying every 5 s) and starts the consumers (`onModuleInit`). */
    fun start() = consumer.start(this)

    override fun onDelivery(queue: SimulationQueue, message: IncomingMessage?) {
        state.locked {
            // a frame on a closing (or replaced) channel is ignored, like amqplib does; the broker requeues it
            if (message != null && message.isStale) return@locked
            consumeMessage(queue, message)
        }
    }

    /** `consumeMessage(queue, message)`; runs under [SimulationState.lock]. */
    private fun SimulationState.consumeMessage(queue: SimulationQueue, message: IncomingMessage?) {
        val maxMessages = variables.number(queue.perMinuteVariable)
        val delaySeconds = variables.number(queue.processingTimeVariable)
        val count = messageCounts.getValue(queue)
        if (count.toDouble() >= maxMessages) {
            log.debug("[{}] Maximum message count reached. Pausing processing until reset.", queue.queueName)
            // not acknowledged: the message stays unacked until the channel is closed at the next minute
            processingAllowed[queue] = false
            return
        }
        if (message == null) return
        // `const { data } = JSON.parse(msg.content.toString())`
        val parsed = try {
            JsJson.parse(String(message.body, Charsets.UTF_8))
        } catch (e: IllegalArgumentException) {
            // the throw escapes amqplib's frame handler -> connection 'error' without listener -> crash (no ack)
            fatal.fatal("[${queue.queueName}] Unparseable message: ${e.message}", e)
            return
        }
        if (parsed.isNull) {
            fatal.fatal("[${queue.queueName}] Cannot destructure property 'data' of null", null)
            return
        }
        val data: JsonNode? = parsed.get("data")
        log.debug(
            "[{}] Processing message: {} [{}/{}]",
            queue.queueName, data?.let { JsJson.stringify(it) }, count + 1, Js.numberToString(maxMessages)
        )
        message.ack()

        // check if message was already manually updated
        if (data == null || data.isNull) {
            fatal.fatal(
                "[${queue.queueName}] Cannot read properties of ${if (data == null) "undefined" else "null"} " +
                    "(reading '${queue.idField}')", null
            )
            return
        }
        if (isBlocked(queue, data)) return

        messageCounts[queue] = count + 1
        // simulate processing
        log.info("[{}] Sending Event Update after [{}]s", queue.queueName, Js.numberToString(delaySeconds))
        val delayMillis = Js.timeoutMillis(delaySeconds * 1000)
        scope.launch {
            delay(delayMillis)
            val first = data.get("paymentId")
            val id = if (JsJson.truthy(first)) first else data.get("shipmentId")
            if (id == null || !JsJson.truthy(id)) {
                fatal.fatal("No ID found in message", null)
                return@launch
            }
            buildEventUpdate(queue, id)
        }
    }

    /** `isBlocked(queue, data)`: looks the registration up, removes it, returns its `blocked` flag. */
    private fun SimulationState.isBlocked(queue: SimulationQueue, data: JsonNode): Boolean {
        val idNode = data.get(queue.idField)
        // strict equality with the stored (string) ids
        val id = if (idNode != null && idNode.isString) idNode.stringValue() else null
        val repository = repository(queue)
        if (queue == SimulationQueue.SHIPMENTS) log.info("Checking if shipment is blocked: {}", JsJson.toJsString(idNode))
        val record = repository.findById(id)
        if (record == null) {
            // can happen if simulation service was restarted
            log.error(
                "{} not found: {}",
                if (queue == SimulationQueue.PAYMENTS) "Payment" else "Shipment", JsJson.toJsString(idNode)
            )
            return false
        }
        // remove the registration from memory since it will be processed
        repository.delete(id)
        return record.blocked
    }

    /** `buildEventUpdate(queue, id)`: Bernoulli draw with the success rate read now, then the callback. */
    private fun buildEventUpdate(queue: SimulationQueue, id: JsonNode) {
        val successRate = variables.number(queue.successRateVariable)
        val successful = random() < successRate
        when (queue) {
            SimulationQueue.PAYMENTS -> connector.sendUpdateToPayment(id, if (successful) "SUCCEEDED" else "FAILED")
            SimulationQueue.SHIPMENTS -> connector.sendUpdateToShipment(id, if (successful) "DELIVERED" else "FAILED")
        }
    }

    /**
     * `@Cron('0 * * * * *') resetMessageCounts()`: resets all counts; if a queue was paused and its limit is > 0,
     * closes and reopens the broker connection so the held (unacked) messages are redelivered.
     */
    fun resetMessageCounts() {
        val reconnect = state.locked {
            log.debug("Resetting message counts.")
            var reconnect = false
            SimulationQueue.entries.forEach { queue ->
                val maxMessages = variables.number(queue.perMinuteVariable)
                if (processingAllowed.getValue(queue) == false && maxMessages > 0.0) reconnect = true
                messageCounts[queue] = 0
                processingAllowed[queue] = true
            }
            // Node closes the channel synchronously in the same tick: nothing more is consumed from it
            if (reconnect) consumer.markClosing()
            reconnect
        }
        if (reconnect) {
            try {
                log.debug("Reconnecting to RabbitMQ...")
                consumer.reconnect()
            } catch (e: Exception) {
                // the original then calls consume() on the closed channel -> unhandled rejection -> crash
                fatal.fatal("Failed to reconnect to RabbitMQ", e)
            }
        }
    }

    /** `onApplicationShutdown`. */
    fun shutdown() = consumer.shutdown()
}
