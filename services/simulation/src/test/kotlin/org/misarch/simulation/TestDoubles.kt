package org.misarch.simulation

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.misarch.simulation.config.SimulationVariables
import org.misarch.simulation.connector.CallbackTransport
import org.misarch.simulation.connector.ConnectorService
import org.misarch.simulation.js.JsJson
import org.misarch.simulation.processing.DeliverySink
import org.misarch.simulation.processing.EventProcessor
import org.misarch.simulation.processing.FatalErrorHandler
import org.misarch.simulation.processing.IncomingMessage
import org.misarch.simulation.processing.QueueConsumer
import org.misarch.simulation.processing.RegistrationPublisher
import org.misarch.simulation.processing.SimulationQueue
import org.misarch.simulation.processing.SimulationState
import org.misarch.simulation.provider.ProviderService
import org.springframework.mock.env.MockEnvironment
import tools.jackson.databind.JsonNode
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList

/** A recorded callback request (virtual time in ms if a scheduler is given). */
data class CallbackCall(val uri: String, val body: String, val time: Long)

/** Records callbacks; [status] decides the answer per attempt number (1-based), throwing = network error. */
class RecordingTransport(private val scheduler: TestCoroutineScheduler? = null) : CallbackTransport {
    val calls = CopyOnWriteArrayList<CallbackCall>()
    var status: (Int) -> Int = { 200 }

    override suspend fun post(uri: URI, json: String): Int {
        calls += CallbackCall(uri.toString(), json, scheduler?.currentTime ?: 0)
        return status(calls.size)
    }
}

class RecordingFatalHandler : FatalErrorHandler {
    val messages = CopyOnWriteArrayList<String>()
    override fun fatal(message: String, cause: Throwable?) {
        messages += message
    }
}

class FakeConsumer : QueueConsumer {
    var markClosingCalls = 0
    var reconnectCalls = 0
    var failReconnect = false
    override fun start(sink: DeliverySink) = Unit
    override fun markClosing() {
        markClosingCalls++
    }

    override fun reconnect() {
        reconnectCalls++
        if (failReconnect) throw IllegalStateException("broker down")
    }

    override fun shutdown() = Unit
}

class RecordingPublisher : RegistrationPublisher {
    val emitted = CopyOnWriteArrayList<Pair<SimulationQueue, String>>()
    override fun emit(queue: SimulationQueue, data: JsonNode) {
        emitted += queue to JsJson.stringify(data)
    }
}

/** A message as amqplib would deliver it; records its ack. */
class TestMessage(json: String, stale: Boolean = false) {
    var acked = false
    val message = IncomingMessage(json.toByteArray(), { acked = true }, { stale })
}

fun paymentMessage(paymentId: String, extra: String = "") =
    """{"pattern":"register-payment","data":{"paymentId":"$paymentId","amount":10,"paymentType":"prepayment"$extra}}"""

fun shipmentMessage(shipmentId: String, extra: String = "") =
    """{"pattern":"register-shipment","data":{"shipmentId":"$shipmentId"$extra}}"""

/** Processor + collaborators with fakes, timers on [scope] (virtual time in tests). */
class Fixture(scope: CoroutineScope, scheduler: TestCoroutineScheduler?, env: Map<String, String> = DEFAULT_ENV) {
    val environment = MockEnvironment().apply { env.forEach { (k, v) -> withProperty(k, v) } }
    val variables = SimulationVariables(environment)
    val state = SimulationState()
    val transport = RecordingTransport(scheduler)
    val connector = ConnectorService(variables, transport, scope)
    val consumer = FakeConsumer()
    val fatal = RecordingFatalHandler()
    val randoms = ArrayDeque<Double>()
    val processor = EventProcessor(state, variables, connector, consumer, fatal, scope) { randoms.removeFirstOrNull() ?: 0.0 }
    val publisher = RecordingPublisher()
    val providers = ProviderService(state, publisher, connector)

    fun set(json: String) = variables.setVariables(JsJson.parse(json))

    fun deliver(queue: SimulationQueue, json: String, stale: Boolean = false): TestMessage =
        TestMessage(json, stale).also { processor.onDelivery(queue, it.message) }

    companion object {
        val DEFAULT_ENV = mapOf("PAYMENT_URL" to "http://payment:8080", "SHIPMENT_URL" to "http://shipment:8080")
    }
}
