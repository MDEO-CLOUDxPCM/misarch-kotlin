package org.misarch.simulation

import com.rabbitmq.client.ConnectionFactory
import com.rabbitmq.client.GetResponse
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.misarch.simulation.amqp.RabbitConsumer
import org.misarch.simulation.amqp.RabbitPublisher
import org.misarch.simulation.model.RegisterPaymentDTO
import org.misarch.simulation.model.SimulationQueue
import org.misarch.simulation.service.ProcessingService
import java.io.IOException
import java.net.URI
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** The AMQP adapters against a real RabbitMQ, each test in its own vhost (test plan 10-12). */
class RabbitAdapterTest {

    /** Records the consumed bodies; [acknowledge] decides per body whether the message is acked. */
    private class RecordingProcessing(var acknowledge: (String) -> Boolean = { true }) {
        val deliveries = LinkedBlockingQueue<Pair<SimulationQueue, String>>()
        var reconnect = true
        val service = mockk<ProcessingService> {
            every { consume(any(), any()) } answers {
                val body = String(secondArg<ByteArray>())
                deliveries += firstArg<SimulationQueue>() to body
                acknowledge(body)
            }
            every { resetMessageCounts() } answers { reconnect }
        }

        fun next(): Pair<SimulationQueue, String> =
            deliveries.poll(10, TimeUnit.SECONDS) ?: error("no delivery within 10 s")
    }

    private fun properties(url: String) = Fixture.DEFAULT_PROPERTIES.copy(rabbitmqUrl = URI(url))

    private fun rawChannel(url: String) = ConnectionFactory().apply { setUri(url) }.newConnection().createChannel()

    @Test
    fun `publisher sends pattern and data, transient, to the durable queue`() {
        val url = RabbitTestBroker.vhost("wire")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val publisher = RabbitPublisher(properties(url), testJsonMapper, scope)
        try {
            val data = RegisterPaymentDTO(UUID.fromString("a8a1f5f2-6b2e-4c1e-9d5c-0d1e2f3a4b5c"), 10.0, "invoice")
            publisher.publish(SimulationQueue.PAYMENTS, data)
            val connection = ConnectionFactory().apply { setUri(url) }.newConnection()
            var response: GetResponse? = null
            repeat(100) {
                if (response == null) {
                    // a fresh channel per attempt: basicGet on a not yet declared queue closes the channel
                    response = runCatching { connection.createChannel().use { it.basicGet("payments-queue", true) } }.getOrNull()
                    if (response == null) Thread.sleep(100)
                }
            }
            val message = response ?: error("nothing published")
            assertThat(String(message.body)).isEqualTo(
                """{"pattern":"register-payment","data":{"paymentId":"a8a1f5f2-6b2e-4c1e-9d5c-0d1e2f3a4b5c","amount":10.0,"paymentType":"invoice"}}"""
            )
            assertThat(message.props.deliveryMode).isEqualTo(1)
            assertThat(message.props.contentType).isNull()
            // the queue is durable: redeclaring it non-durable fails (PRECONDITION_FAILED)
            val channel = connection.createChannel()
            assertThrows<IOException> { channel.queueDeclare("payments-queue", false, false, false, null) }
            connection.close()
        } finally {
            publisher.destroy()
            scope.cancel()
        }
    }

    @Test
    fun `consumer gets backlog at start, unlimited prefetch, held messages are redelivered after the reset`() {
        val url = RabbitTestBroker.vhost("consume")
        val raw = rawChannel(url)
        SimulationQueue.entries.forEach { raw.queueDeclare(it.queueName, true, false, false, null) }
        // backlog published before the simulation starts (e.g. by a previous instance)
        repeat(50) { raw.basicPublish("", "shipments-queue", null, """{"data":{"shipmentId":"s$it"}}""".toByteArray()) }

        val fatal = RecordingFatalHandler()
        // the first 10 are processed, the others held (rate limit)
        val processing = RecordingProcessing { body -> Regex("\"s(\\d+)\"").find(body)!!.groupValues[1].toInt() < 10 }
        val consumer = RabbitConsumer(properties(url), processing.service, fatal)
        consumer.start()
        try {
            // no prefetch limit: all 50 messages are delivered at once
            val held = List(50) { processing.next() }
            assertThat(held.map { it.first }).allMatch { it == SimulationQueue.SHIPMENTS }

            processing.acknowledge = { true }
            consumer.resetRateLimit()
            // the 40 unacked messages come back on the new channel, in order, and nothing else
            val redelivered = List(40) { processing.next().second }
            assertThat(redelivered).containsExactlyElementsOf((10 until 50).map { """{"data":{"shipmentId":"s$it"}}""" })
            assertThat(processing.deliveries.poll(1, TimeUnit.SECONDS)).isNull()
            assertThat(fatal.messages).isEmpty()

            // a reset without a paused queue keeps the channel
            processing.reconnect = false
            consumer.resetRateLimit()
            assertThat(processing.deliveries.poll(1, TimeUnit.SECONDS)).isNull()
            assertThat(raw.messageCount("shipments-queue")).isZero()
        } finally {
            consumer.stop()
        }
    }

    @Test
    fun `broker-side connection close is fatal`() {
        val url = RabbitTestBroker.vhost("crash")
        val fatal = RecordingFatalHandler()
        val consumer = RabbitConsumer(properties(url), RecordingProcessing().service, fatal)
        consumer.start()
        try {
            RabbitTestBroker.closeAllConnections("crash")
            repeat(100) { if (fatal.messages.isEmpty()) Thread.sleep(100) }
            assertThat(fatal.messages).isNotEmpty()
            assertThat(fatal.messages.first()).startsWith("RabbitMQ ")
        } finally {
            consumer.stop()
        }
    }
}
