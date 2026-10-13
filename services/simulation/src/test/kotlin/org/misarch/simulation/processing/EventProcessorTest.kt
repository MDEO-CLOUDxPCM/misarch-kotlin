package org.misarch.simulation.processing

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.misarch.simulation.CallbackCall
import org.misarch.simulation.Fixture
import org.misarch.simulation.paymentMessage
import org.misarch.simulation.shipmentMessage
import java.util.UUID

/** Queue processing semantics with virtual time (docs/specs/simulation.md §4, test plan 2-8). */
class EventProcessorTest {

    private fun TestScope.fixture(env: Map<String, String> = Fixture.DEFAULT_ENV) = Fixture(backgroundScope, testScheduler, env)

    private fun TestScope.advance(millis: Long) {
        advanceTimeBy(millis)
        runCurrent()
    }

    @Test
    fun `payment is acked at once and called back after exactly 5 s`() = runTest {
        val f = fixture()
        val id = UUID.randomUUID().toString()
        f.randoms += 0.5
        val msg = f.deliver(SimulationQueue.PAYMENTS, paymentMessage(id))
        assertThat(msg.acked).isTrue()
        advance(4999)
        assertThat(f.transport.calls).isEmpty()
        advance(1)
        assertThat(f.transport.calls).containsExactly(
            CallbackCall("http://payment:8080/payment/update-payment-status", """{"paymentId":"$id","status":"SUCCEEDED"}""", 5000)
        )
        assertThat(f.fatal.messages).isEmpty()
    }

    @Test
    fun `success iff Math random is below the success rate`() = runTest {
        val f = fixture()
        val ids = List(3) { UUID.randomUUID().toString() }
        f.randoms += listOf(0.9499, 0.95, 0.99)
        ids.forEach { f.deliver(SimulationQueue.PAYMENTS, paymentMessage(it)) }
        advance(5000)
        assertThat(f.transport.calls.map { it.body }).containsExactly(
            """{"paymentId":"${ids[0]}","status":"SUCCEEDED"}""",
            """{"paymentId":"${ids[1]}","status":"FAILED"}""",
            """{"paymentId":"${ids[2]}","status":"FAILED"}""",
        )
    }

    @Test
    fun `shipment callback goes to shipment id status with DELIVERED or FAILED`() = runTest {
        val f = fixture()
        val (a, b) = UUID.randomUUID().toString() to UUID.randomUUID().toString()
        f.randoms += listOf(0.1, 0.97)
        f.deliver(SimulationQueue.SHIPMENTS, shipmentMessage(a, ""","ref":"x","address":{"city":"S"}"""))
        f.deliver(SimulationQueue.SHIPMENTS, shipmentMessage(b))
        advance(5000)
        assertThat(f.transport.calls).containsExactly(
            CallbackCall("http://shipment:8080/shipment/$a/status", """{"status":"DELIVERED"}""", 5000),
            CallbackCall("http://shipment:8080/shipment/$b/status", """{"status":"FAILED"}""", 5000),
        )
    }

    @Test
    fun `processing time is read at consumption, success rate when the timer fires`() = runTest {
        val f = fixture()
        f.set("""{"PAYMENT_PROCESSING_TIME":2,"PAYMENT_SUCCESS_RATE":1}""")
        val id = UUID.randomUUID().toString()
        f.randoms += 0.5
        f.deliver(SimulationQueue.PAYMENTS, paymentMessage(id))
        f.set("""{"PAYMENT_PROCESSING_TIME":60,"PAYMENT_SUCCESS_RATE":0}""")
        advance(2000)
        assertThat(f.transport.calls).containsExactly(
            CallbackCall("http://payment:8080/payment/update-payment-status", """{"paymentId":"$id","status":"FAILED"}""", 2000)
        )
    }

    @Test
    fun `processing time 0 or NaN becomes 1 ms (setTimeout clamp)`() = runTest {
        val f = fixture()
        f.set("""{"PAYMENT_PROCESSING_TIME":0,"SHIPMENT_PROCESSING_TIME":"abc"}""")
        f.deliver(SimulationQueue.PAYMENTS, paymentMessage(UUID.randomUUID().toString()))
        f.deliver(SimulationQueue.SHIPMENTS, shipmentMessage(UUID.randomUUID().toString()))
        advance(1)
        assertThat(f.transport.calls.map { it.time }).containsExactly(1L, 1L)
    }

    @Test
    fun `no worker limit - every message has its own timer`() = runTest {
        val f = fixture()
        repeat(500) { f.deliver(SimulationQueue.PAYMENTS, paymentMessage(UUID.randomUUID().toString())) }
        advance(5000)
        assertThat(f.transport.calls).hasSize(500).allMatch { it.time == 5000L }
    }

    @Test
    fun `rate limit holds messages unacked, the minute reset reconnects and they are processed again`() = runTest {
        val f = fixture()
        f.set("""{"PAYMENTS_PER_MINUTE":2}""")
        val ids = List(3) { UUID.randomUUID().toString() }
        val first = ids.map { f.deliver(SimulationQueue.PAYMENTS, paymentMessage(it)) }
        assertThat(first.map { it.acked }).containsExactly(true, true, false)
        assertThat(f.state.processingAllowed[SimulationQueue.PAYMENTS]).isFalse()
        assertThat(f.state.processingAllowed[SimulationQueue.SHIPMENTS]).isTrue()
        // shipments are limited independently
        assertThat(f.deliver(SimulationQueue.SHIPMENTS, shipmentMessage(UUID.randomUUID().toString())).acked).isTrue()

        f.processor.resetMessageCounts()
        assertThat(f.consumer.markClosingCalls).isEqualTo(1)
        assertThat(f.consumer.reconnectCalls).isEqualTo(1)
        assertThat(f.state.messageCounts[SimulationQueue.PAYMENTS]).isEqualTo(0)
        assertThat(f.state.processingAllowed[SimulationQueue.PAYMENTS]).isTrue()

        // the broker redelivers the held message on the new channel
        assertThat(f.deliver(SimulationQueue.PAYMENTS, paymentMessage(ids[2])).acked).isTrue()
        advance(5000)
        assertThat(f.transport.calls).hasSize(4)
    }

    @Test
    fun `reset without a paused queue does not reconnect`() = runTest {
        val f = fixture()
        f.deliver(SimulationQueue.PAYMENTS, paymentMessage(UUID.randomUUID().toString()))
        f.processor.resetMessageCounts()
        assertThat(f.consumer.markClosingCalls).isZero()
        assertThat(f.consumer.reconnectCalls).isZero()
        assertThat(f.state.messageCounts[SimulationQueue.PAYMENTS]).isZero()
    }

    @Test
    fun `limit 0 holds every message and never reconnects (quirk Q4)`() = runTest {
        val f = fixture()
        f.set("""{"PAYMENTS_PER_MINUTE":0}""")
        assertThat(f.deliver(SimulationQueue.PAYMENTS, paymentMessage(UUID.randomUUID().toString())).acked).isFalse()
        f.processor.resetMessageCounts()
        assertThat(f.consumer.reconnectCalls).isZero()
    }

    @Test
    fun `limit NaN never limits`() = runTest {
        val f = fixture()
        f.set("""{"PAYMENTS_PER_MINUTE":"abc"}""")
        val messages = List(5) { f.deliver(SimulationQueue.PAYMENTS, paymentMessage(UUID.randomUUID().toString())) }
        assertThat(messages).allMatch { it.acked }
    }

    @Test
    fun `failed reconnect at the minute boundary is fatal`() = runTest {
        val f = fixture()
        f.set("""{"PAYMENTS_PER_MINUTE":1}""")
        repeat(2) { f.deliver(SimulationQueue.PAYMENTS, paymentMessage(UUID.randomUUID().toString())) }
        f.consumer.failReconnect = true
        f.processor.resetMessageCounts()
        assertThat(f.fatal.messages).containsExactly("Failed to reconnect to RabbitMQ")
    }

    @Test
    fun `stale deliveries (closing channel) are neither processed nor acked`() = runTest {
        val f = fixture()
        val msg = f.deliver(SimulationQueue.PAYMENTS, paymentMessage(UUID.randomUUID().toString()), stale = true)
        assertThat(msg.acked).isFalse()
        assertThat(f.state.messageCounts[SimulationQueue.PAYMENTS]).isZero()
        advance(10000)
        assertThat(f.transport.calls).isEmpty()
    }

    @Test
    fun `consumer cancellation sets processingAllowed only when over the limit`() = runTest {
        val f = fixture()
        f.processor.onDelivery(SimulationQueue.PAYMENTS, null)
        assertThat(f.state.processingAllowed[SimulationQueue.PAYMENTS]).isTrue()
        f.set("""{"PAYMENTS_PER_MINUTE":0}""")
        f.processor.onDelivery(SimulationQueue.PAYMENTS, null)
        assertThat(f.state.processingAllowed[SimulationQueue.PAYMENTS]).isFalse()
    }

    @Test
    fun `manual update blocks the registration - one callback only, message not counted`() = runTest {
        val f = fixture()
        val id = UUID.randomUUID().toString()
        f.providers.register(SimulationQueue.PAYMENTS, f.parseBody("""{"paymentId":"$id","amount":1,"paymentType":"invoice"}"""))
        f.providers.update(SimulationQueue.PAYMENTS, id, "INKASSO")
        runCurrent()
        assertThat(f.transport.calls.map { it.body }).containsExactly("""{"paymentId":"$id","status":"INKASSO"}""")
        val msg = f.deliver(SimulationQueue.PAYMENTS, paymentMessage(id))
        assertThat(msg.acked).isTrue()
        assertThat(f.state.messageCounts[SimulationQueue.PAYMENTS]).isZero()
        assertThat(f.providers.findAll(SimulationQueue.PAYMENTS).size()).isZero()
        advance(10000)
        assertThat(f.transport.calls).hasSize(1)
    }

    @Test
    fun `registration is removed when its message is consumed - a later update fails`() = runTest {
        val f = fixture()
        val id = UUID.randomUUID().toString()
        f.providers.register(SimulationQueue.SHIPMENTS, f.parseBody("""{"shipmentId":"$id"}"""))
        assertThat(f.providers.findAll(SimulationQueue.SHIPMENTS).toString()).isEqualTo("""[{"id":"$id","blocked":false}]""")
        f.deliver(SimulationQueue.SHIPMENTS, shipmentMessage(id))
        assertThat(f.providers.findAll(SimulationQueue.SHIPMENTS).size()).isZero()
        org.junit.jupiter.api.assertThrows<org.misarch.simulation.provider.ProviderRecordNotFoundException> {
            f.providers.update(SimulationQueue.SHIPMENTS, id, "DELIVERED")
        }
    }

    @Test
    fun `duplicate registrations - the first consumption removes all, the second message is processed (Q13)`() = runTest {
        val f = fixture()
        val id = UUID.randomUUID().toString()
        repeat(2) { f.providers.register(SimulationQueue.PAYMENTS, f.parseBody("""{"paymentId":"$id","amount":1,"paymentType":"invoice"}""")) }
        f.providers.update(SimulationQueue.PAYMENTS, id, "FAILED") // blocks the first entry only
        runCurrent()
        f.deliver(SimulationQueue.PAYMENTS, paymentMessage(id)) // blocked -> dropped, both entries deleted
        f.deliver(SimulationQueue.PAYMENTS, paymentMessage(id)) // not found -> processed
        assertThat(f.state.messageCounts[SimulationQueue.PAYMENTS]).isEqualTo(1)
        advance(5000)
        assertThat(f.transport.calls).hasSize(2)
    }

    @Test
    fun `callback id is the id of the queue's kind (fix S2)`() = runTest {
        val f = fixture()
        val shipmentId = UUID.randomUUID().toString()
        val paymentId = UUID.randomUUID().toString()
        f.deliver(SimulationQueue.SHIPMENTS, shipmentMessage(shipmentId, ""","paymentId":"p-1""""))
        f.deliver(SimulationQueue.PAYMENTS, paymentMessage(paymentId, ""","shipmentId":"s-1""""))
        advance(5000)
        assertThat(f.transport.calls.map { it.uri to it.body }).containsExactly(
            "http://shipment:8080/shipment/$shipmentId/status" to """{"status":"DELIVERED"}""",
            "http://payment:8080/payment/update-payment-status" to """{"paymentId":"$paymentId","status":"SUCCEEDED"}""",
        )
    }

    @Test
    fun `unparseable messages are acked and dropped, not fatal (fix S1)`() = runTest {
        val f = fixture()
        for (content in listOf("not json", "null", "[1]", "5")) {
            val msg = f.deliver(SimulationQueue.PAYMENTS, content)
            assertThat(msg.acked).describedAs(content).isTrue()
        }
        assertThat(f.fatal.messages).isEmpty()
        assertThat(f.state.messageCounts[SimulationQueue.PAYMENTS]).isZero()
        advance(10000)
        assertThat(f.transport.calls).isEmpty()
    }

    @Test
    fun `messages without data or without the queue's id are acked and dropped, not counted (fix S1)`() = runTest {
        val f = fixture()
        val dropped = listOf(
            SimulationQueue.PAYMENTS to """{"pattern":"register-payment"}""",
            SimulationQueue.PAYMENTS to """{"pattern":"register-payment","data":null}""",
            SimulationQueue.PAYMENTS to """{"pattern":"register-payment","data":{"amount":3}}""",
            SimulationQueue.PAYMENTS to """{"pattern":"register-payment","data":{"paymentId":""}}""",
            // a payment id on the shipments queue is not a shipment id
            SimulationQueue.SHIPMENTS to """{"pattern":"register-shipment","data":{"paymentId":"p-1"}}""",
        ).map { (queue, json) -> f.deliver(queue, json) }
        assertThat(dropped).allMatch { it.acked }
        assertThat(f.fatal.messages).isEmpty()
        assertThat(f.state.messageCounts.values).containsOnly(0)
        // a valid message afterwards is processed normally
        val id = UUID.randomUUID().toString()
        f.deliver(SimulationQueue.PAYMENTS, paymentMessage(id))
        advance(5000)
        assertThat(f.transport.calls.map { it.body }).containsExactly("""{"paymentId":"$id","status":"SUCCEEDED"}""")
        assertThat(f.fatal.messages).isEmpty()
    }

    private fun Fixture.parseBody(json: String) = org.misarch.simulation.js.JsJson.parse(json)
}
