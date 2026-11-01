package org.misarch.simulation

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.misarch.simulation.model.RegisterPaymentDTO
import org.misarch.simulation.model.RegisterShipmentDTO
import org.misarch.simulation.model.SimulationQueue
import org.misarch.simulation.model.UpdatePaymentDTO
import org.misarch.simulation.model.UpdateShipmentDTO
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/** Queue processing semantics with virtual time (docs/specs/simulation.md §4, test plan 2-8). */
class ProcessingServiceTest {

    private fun TestScope.fixture() = Fixture(backgroundScope, testScheduler)

    private fun TestScope.advance(millis: Long) {
        advanceTimeBy(millis)
        runCurrent()
    }

    private fun newId() = UUID.randomUUID().toString()

    @Test
    fun `payment is acked at once and called back after exactly 5 s`() = runTest {
        val f = fixture()
        val id = newId()
        f.random.values += 0.5
        assertThat(f.deliver(SimulationQueue.PAYMENTS, paymentMessage(id))).isTrue()
        advance(4999)
        assertThat(f.callbacks.calls).isEmpty()
        advance(1)
        assertThat(f.callbacks.calls).containsExactly(
            CallbackCall("http://payment:8080/payment/update-payment-status", """{"paymentId":"$id","status":"SUCCEEDED"}""", 5000)
        )
    }

    @Test
    fun `success iff the random number is below the success rate`() = runTest {
        val f = fixture()
        val ids = List(3) { newId() }
        f.random.values += listOf(0.9499, 0.95, 0.99)
        ids.forEach { f.deliver(SimulationQueue.PAYMENTS, paymentMessage(it)) }
        advance(5000)
        assertThat(f.callbacks.calls.map { it.body }).containsExactly(
            """{"paymentId":"${ids[0]}","status":"SUCCEEDED"}""",
            """{"paymentId":"${ids[1]}","status":"FAILED"}""",
            """{"paymentId":"${ids[2]}","status":"FAILED"}""",
        )
    }

    @Test
    fun `shipment callback goes to shipment id status with DELIVERED or FAILED`() = runTest {
        val f = fixture()
        val (a, b) = newId() to newId()
        f.random.values += listOf(0.1, 0.97)
        f.deliver(SimulationQueue.SHIPMENTS, shipmentMessage(a, ""","ref":"x","address":{"city":"S"}"""))
        f.deliver(SimulationQueue.SHIPMENTS, shipmentMessage(b))
        advance(5000)
        assertThat(f.callbacks.calls).containsExactly(
            CallbackCall("http://shipment:8080/shipment/$a/status", """{"status":"DELIVERED"}""", 5000),
            CallbackCall("http://shipment:8080/shipment/$b/status", """{"status":"FAILED"}""", 5000),
        )
    }

    @Test
    fun `processing time is read at consumption, success rate when the timer fires`() = runTest {
        val f = fixture()
        f.config.paymentProcessingTime.value = 2
        f.config.paymentSuccessRate.value = 1.0
        val id = newId()
        f.random.values += 0.5
        f.deliver(SimulationQueue.PAYMENTS, paymentMessage(id))
        f.config.paymentProcessingTime.value = 60
        f.config.paymentSuccessRate.value = 0.0
        advance(2000)
        assertThat(f.callbacks.calls).containsExactly(
            CallbackCall("http://payment:8080/payment/update-payment-status", """{"paymentId":"$id","status":"FAILED"}""", 2000)
        )
    }

    @Test
    fun `processing time 0 or negative means no delay`() = runTest {
        val f = fixture()
        f.config.paymentProcessingTime.value = 0
        f.config.shipmentProcessingTime.value = -3
        f.deliver(SimulationQueue.PAYMENTS, paymentMessage(newId()))
        f.deliver(SimulationQueue.SHIPMENTS, shipmentMessage(newId()))
        runCurrent()
        assertThat(f.callbacks.calls.map { it.time }).containsExactly(0L, 0L)
    }

    @Test
    fun `no worker limit - every message has its own timer`() = runTest {
        val f = fixture()
        repeat(500) { f.deliver(SimulationQueue.PAYMENTS, paymentMessage(newId())) }
        advance(5000)
        assertThat(f.callbacks.calls).hasSize(500).allMatch { it.time == 5000L }
    }

    @Test
    fun `rate limit holds messages unacked, the minute reset asks for a reconnect and they are processed again`() = runTest {
        val f = fixture()
        f.config.paymentsPerMinute.value = 2
        val ids = List(3) { newId() }
        assertThat(ids.map { f.deliver(SimulationQueue.PAYMENTS, paymentMessage(it)) }).containsExactly(true, true, false)
        // shipments are limited independently
        assertThat(f.deliver(SimulationQueue.SHIPMENTS, shipmentMessage(newId()))).isTrue()

        assertThat(f.processor.resetMessageCounts()).isTrue()
        // the broker redelivers the held message on the new channel; the window is new
        assertThat(f.deliver(SimulationQueue.PAYMENTS, paymentMessage(ids[2]))).isTrue()
        assertThat(f.deliver(SimulationQueue.PAYMENTS, paymentMessage(newId()))).isTrue()
        advance(5000)
        assertThat(f.callbacks.calls).hasSize(5)
        // the queue was not paused in this window
        assertThat(f.processor.resetMessageCounts()).isFalse()
    }

    @Test
    fun `reset without a paused queue does not ask for a reconnect`() = runTest {
        val f = fixture()
        f.deliver(SimulationQueue.PAYMENTS, paymentMessage(newId()))
        assertThat(f.processor.resetMessageCounts()).isFalse()
    }

    @Test
    fun `limit 0 holds every message and never asks for a reconnect`() = runTest {
        val f = fixture()
        f.config.paymentsPerMinute.value = 0
        assertThat(f.deliver(SimulationQueue.PAYMENTS, paymentMessage(newId()))).isFalse()
        assertThat(f.processor.resetMessageCounts()).isFalse()
    }

    @Test
    fun `consumer cancellation pauses the queue only when over the limit`() = runTest {
        val f = fixture()
        f.processor.onConsumerCancelled(SimulationQueue.PAYMENTS)
        assertThat(f.processor.resetMessageCounts()).isFalse()
        f.config.paymentsPerMinute.value = 0
        f.processor.onConsumerCancelled(SimulationQueue.PAYMENTS)
        f.config.paymentsPerMinute.value = 5
        assertThat(f.processor.resetMessageCounts()).isTrue()
    }

    @Test
    fun `manual update blocks the registration - one callback only, message not counted`() = runTest {
        val f = fixture()
        f.config.paymentsPerMinute.value = 1
        val id = UUID.randomUUID()
        f.payments.register(RegisterPaymentDTO(id, 1.0, "invoice"))
        f.payments.update(UpdatePaymentDTO(id, "INKASSO"))
        runCurrent()
        assertThat(f.callbacks.calls.map { it.body }).containsExactly("""{"paymentId":"$id","status":"INKASSO"}""")
        assertThat(f.deliver(SimulationQueue.PAYMENTS, paymentMessage(id.toString()))).isTrue()
        assertThat(f.payments.findAll()).isEmpty()
        advance(10000)
        assertThat(f.callbacks.calls).hasSize(1)
        // not counted: the limit of 1 still admits the next message
        assertThat(f.deliver(SimulationQueue.PAYMENTS, paymentMessage(newId()))).isTrue()
    }

    @Test
    fun `registration is removed when its message is consumed - a later update fails`() = runTest {
        val f = fixture()
        val id = UUID.randomUUID()
        f.shipments.register(RegisterShipmentDTO(id))
        assertThat(f.shipments.findAll().map { it.id to it.blocked }).containsExactly(id to false)
        f.deliver(SimulationQueue.SHIPMENTS, shipmentMessage(id.toString()))
        assertThat(f.shipments.findAll()).isEmpty()
        val e = assertThrows<ResponseStatusException> { f.shipments.update(UpdateShipmentDTO(id, "DELIVERED")) }
        assertThat(e.statusCode.value()).isEqualTo(404)
    }

    @Test
    fun `duplicate registrations - the first is kept, its consumption ends it, the second message is processed`() =
        runTest {
            val f = fixture()
            val id = UUID.randomUUID()
            f.payments.register(RegisterPaymentDTO(id, 1.0, "invoice"))
            f.payments.register(RegisterPaymentDTO(id, 1.0, "prepayment"))
            assertThat(f.payments.findAll().map { it.paymentType }).containsExactly("invoice")
            f.payments.update(UpdatePaymentDTO(id, "FAILED"))
            runCurrent()
            f.deliver(SimulationQueue.PAYMENTS, paymentMessage(id.toString())) // blocked -> dropped, registration ended
            f.deliver(SimulationQueue.PAYMENTS, paymentMessage(id.toString())) // not found -> processed
            advance(5000)
            assertThat(f.callbacks.calls.map { it.body }).containsExactly(
                """{"paymentId":"$id","status":"FAILED"}""",
                """{"paymentId":"$id","status":"SUCCEEDED"}""",
            )
        }

    @Test
    fun `callback id is the id of the queue's kind`() = runTest {
        val f = fixture()
        val shipmentId = newId()
        val paymentId = newId()
        f.deliver(SimulationQueue.SHIPMENTS, shipmentMessage(shipmentId, ""","paymentId":"p-1""""))
        f.deliver(SimulationQueue.PAYMENTS, paymentMessage(paymentId, ""","shipmentId":"s-1""""))
        advance(5000)
        assertThat(f.callbacks.calls.map { it.uri to it.body }).containsExactly(
            "http://shipment:8080/shipment/$shipmentId/status" to """{"status":"DELIVERED"}""",
            "http://payment:8080/payment/update-payment-status" to """{"paymentId":"$paymentId","status":"SUCCEEDED"}""",
        )
    }

    @Test
    fun `unparseable messages are acked and dropped, not counted`() = runTest {
        val f = fixture()
        f.config.paymentsPerMinute.value = 1
        for (content in listOf("not json", "null", "[1]", "5", "")) {
            assertThat(f.deliver(SimulationQueue.PAYMENTS, content)).describedAs(content).isTrue()
        }
        advance(10000)
        assertThat(f.callbacks.calls).isEmpty()
        assertThat(f.deliver(SimulationQueue.PAYMENTS, paymentMessage(newId()))).isTrue()
    }

    @Test
    fun `messages without data or without the queue's id are acked and dropped, not counted`() = runTest {
        val f = fixture()
        f.config.paymentsPerMinute.value = 1
        f.config.shipmentsPerMinute.value = 1
        val dropped = listOf(
            SimulationQueue.PAYMENTS to """{"pattern":"register-payment"}""",
            SimulationQueue.PAYMENTS to """{"pattern":"register-payment","data":null}""",
            SimulationQueue.PAYMENTS to """{"pattern":"register-payment","data":{"amount":3}}""",
            SimulationQueue.PAYMENTS to """{"pattern":"register-payment","data":{"paymentId":""}}""",
            SimulationQueue.PAYMENTS to """{"pattern":"register-payment","data":{"paymentId":5}}""",
            // a payment id on the shipments queue is not a shipment id
            SimulationQueue.SHIPMENTS to """{"pattern":"register-shipment","data":{"paymentId":"p-1"}}""",
        ).map { (queue, json) -> f.deliver(queue, json) }
        assertThat(dropped).containsOnly(true)
        // a valid message afterwards is processed normally (within the limit of 1)
        val id = newId()
        assertThat(f.deliver(SimulationQueue.PAYMENTS, paymentMessage(id))).isTrue()
        assertThat(f.deliver(SimulationQueue.SHIPMENTS, shipmentMessage(newId()))).isTrue()
        advance(5000)
        assertThat(f.callbacks.calls.map { it.body }).contains("""{"paymentId":"$id","status":"SUCCEEDED"}""")
    }
}
