package org.misarch.simulation.integration

import com.rabbitmq.client.ConnectionFactory
import com.rabbitmq.client.GetResponse
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.misarch.simulation.RecordingFatalHandler
import org.misarch.simulation.amqp.RabbitConsumer
import org.misarch.simulation.amqp.RabbitPublisher
import org.misarch.simulation.js.JsJson
import org.misarch.simulation.processing.DeliverySink
import org.misarch.simulation.processing.IncomingMessage
import org.misarch.simulation.processing.SimulationQueue
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** The AMQP adapters against a real RabbitMQ, each test in its own vhost (test plan 10-12). */
class RabbitAdapterTest {

    private class RecordingSink : DeliverySink {
        val deliveries = LinkedBlockingQueue<Pair<SimulationQueue, IncomingMessage>>()
        override fun onDelivery(queue: SimulationQueue, message: IncomingMessage?) {
            if (message != null) deliveries += queue to message
        }

        fun next(): Pair<SimulationQueue, IncomingMessage> =
            deliveries.poll(10, TimeUnit.SECONDS) ?: error("no delivery within 10 s")
    }

    private fun rawChannel(url: String) = ConnectionFactory().apply { setUri(url) }.newConnection().createChannel()

    @Test
    fun `publisher sends the NestJS ClientRMQ packet, transient, to the durable queue`() {
        val url = RabbitTestBroker.vhost("wire")
        val publisher = RabbitPublisher(url)
        try {
            val data = JsJson.parse("""{"paymentId":"a8a1f5f2-6b2e-4c1e-9d5c-0d1e2f3a4b5c","amount":10,"paymentType":"invoice"}""")
            publisher.emit(SimulationQueue.PAYMENTS, data)
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
                """{"pattern":"register-payment","data":{"paymentId":"a8a1f5f2-6b2e-4c1e-9d5c-0d1e2f3a4b5c","amount":10,"paymentType":"invoice"}}"""
            )
            assertThat(message.props.deliveryMode).isEqualTo(1)
            assertThat(message.props.contentType).isNull()
            // the queue is durable: redeclaring it non-durable fails (PRECONDITION_FAILED)
            val channel = connection.createChannel()
            assertThrows<IOException> { channel.queueDeclare("payments-queue", false, false, false, null) }
            connection.close()
        } finally {
            publisher.shutdown()
        }
    }

    @Test
    fun `consumer gets backlog at start, unlimited prefetch, held messages are redelivered after reconnect`() {
        val url = RabbitTestBroker.vhost("consume")
        val raw = rawChannel(url)
        SimulationQueue.entries.forEach { raw.queueDeclare(it.queueName, true, false, false, null) }
        // backlog published before the simulation starts (e.g. by a previous instance)
        repeat(50) { raw.basicPublish("", "shipments-queue", null, """{"data":{"shipmentId":"s$it"}}""".toByteArray()) }

        val fatal = RecordingFatalHandler()
        val consumer = RabbitConsumer(url, fatal)
        val sink = RecordingSink()
        consumer.start(sink)
        try {
            // no prefetch limit: all 50 unacked messages are delivered at once
            val held = List(50) { sink.next() }
            assertThat(held.map { it.first }).allMatch { it == SimulationQueue.SHIPMENTS }
            held.take(10).forEach { it.second.ack() }
            assertThat(held.none { it.second.isStale }).isTrue()

            consumer.markClosing()
            assertThat(held.last().second.isStale).isTrue()
            consumer.reconnect()
            // the 40 unacked messages come back on the new channel, in order
            val redelivered = List(40) { sink.next() }
            assertThat(redelivered.map { String(it.second.body) })
                .containsExactlyElementsOf((10 until 50).map { """{"data":{"shipmentId":"s$it"}}""" })
            assertThat(redelivered.none { it.second.isStale }).isTrue()
            assertThat(held.last().second.isStale).isTrue()
            assertThat(fatal.messages).isEmpty()
        } finally {
            consumer.shutdown()
        }
    }

    @Test
    fun `broker-side connection close is fatal (the original crashes on the unhandled error event)`() {
        val url = RabbitTestBroker.vhost("crash")
        val fatal = RecordingFatalHandler()
        val consumer = RabbitConsumer(url, fatal)
        consumer.start(RecordingSink())
        try {
            RabbitTestBroker.closeAllConnections("crash")
            repeat(100) { if (fatal.messages.isEmpty()) Thread.sleep(100) }
            assertThat(fatal.messages).isNotEmpty()
            assertThat(fatal.messages.first()).startsWith("RabbitMQ ")
        } finally {
            consumer.shutdown()
        }
    }
}
