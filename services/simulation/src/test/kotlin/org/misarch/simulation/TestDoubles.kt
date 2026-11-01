package org.misarch.simulation

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.misarch.simulation.amqp.FatalErrorHandler
import org.misarch.simulation.amqp.RabbitPublisher
import org.misarch.simulation.config.ExperimentConfigProperties
import org.misarch.simulation.config.SimulationConfigurationProperties
import org.misarch.simulation.ecs.ExperimentConfigService
import org.misarch.simulation.model.SimulationQueue
import org.misarch.simulation.service.CallbackService
import org.misarch.simulation.service.PaymentService
import org.misarch.simulation.service.ProcessingService
import org.misarch.simulation.service.ShipmentService
import org.springframework.context.support.GenericApplicationContext
import org.springframework.http.HttpStatusCode
import org.springframework.http.codec.json.JacksonJsonEncoder
import org.springframework.http.server.reactive.ServerHttpRequest
import org.springframework.mock.http.client.reactive.MockClientHttpRequest
import org.springframework.web.reactive.function.BodyInserter
import org.springframework.web.reactive.function.client.ClientResponse
import org.springframework.web.reactive.function.client.ExchangeStrategies
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.jacksonMapperBuilder
import java.net.URI
import java.util.Optional
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.random.Random

/** JSON mapper with the Kotlin module, like the application's. */
val testJsonMapper: JsonMapper = jacksonMapperBuilder().build()

/** A recorded callback request (virtual time in ms if a scheduler is given). */
data class CallbackCall(val uri: String, val body: String, val time: Long, val contentType: String? = "application/json")

/**
 * A [WebClient] that records the callbacks instead of sending them; [status] decides the answer per attempt number
 * (1-based), throwing = network error.
 */
class RecordingCallbacks(private val scheduler: TestCoroutineScheduler? = null) {
    val calls = CopyOnWriteArrayList<CallbackCall>()
    var status: (Int) -> Int = { 200 }

    private val writeContext = object : BodyInserter.Context {
        private val strategies = ExchangeStrategies.builder()
            .codecs { it.defaultCodecs().jacksonJsonEncoder(JacksonJsonEncoder(testJsonMapper)) }
            .build()

        override fun messageWriters() = strategies.messageWriters()
        override fun serverRequest() = Optional.empty<ServerHttpRequest>()
        override fun hints() = emptyMap<String, Any>()
    }

    val webClient: WebClient = WebClient.builder().exchangeFunction { request ->
        val captured = MockClientHttpRequest(request.method(), request.url())
        request.body().insert(captured, writeContext).then(Mono.defer { captured.bodyAsString }).map { body ->
            val contentType = request.headers().contentType?.toString()
            calls += CallbackCall(request.url().toString(), body, scheduler?.currentTime ?: 0, contentType)
            ClientResponse.create(HttpStatusCode.valueOf(status(calls.size))).build()
        }
    }.build()
}

/** Records fatal errors instead of terminating the JVM. */
class RecordingFatalHandler : FatalErrorHandler(GenericApplicationContext()) {
    val messages = CopyOnWriteArrayList<String>()
    override fun fatal(message: String, cause: Throwable?) {
        messages += message
    }
}

/** Random numbers taken from [values] (0.0 when empty). */
class QueuedRandom : Random() {
    val values = ArrayDeque<Double>()
    override fun nextBits(bitCount: Int): Int = error("not used")
    override fun nextDouble(): Double = values.removeFirstOrNull() ?: 0.0
}

fun paymentMessage(paymentId: String, extra: String = "") =
    """{"pattern":"register-payment","data":{"paymentId":"$paymentId","amount":10.0,"paymentType":"prepayment"$extra}}"""

fun shipmentMessage(shipmentId: String, extra: String = "") =
    """{"pattern":"register-shipment","data":{"shipmentId":"$shipmentId"$extra}}"""

/** Services with recording fakes for the broker and the callbacks, delays on [scope] (virtual time in tests). */
class Fixture(
    scope: CoroutineScope,
    scheduler: TestCoroutineScheduler?,
    properties: SimulationConfigurationProperties = DEFAULT_PROPERTIES,
) {
    val config = ExperimentConfigService(ExperimentConfigProperties())
    val callbacks = RecordingCallbacks(scheduler)
    val callbackService = CallbackService(properties, callbacks.webClient, scope)

    /** The published registrations as JSON */
    val published = CopyOnWriteArrayList<Pair<SimulationQueue, String>>()
    val publisher = mockk<RabbitPublisher> {
        every { publish(any(), any()) } answers { published += firstArg<SimulationQueue>() to testJsonMapper.writeValueAsString(secondArg()) }
    }
    val payments = PaymentService(publisher, callbackService)
    val shipments = ShipmentService(publisher, callbackService)
    val random = QueuedRandom()
    val processor = ProcessingService(config, payments, shipments, callbackService, testJsonMapper, scope, random)

    /** Delivers a message; true if it was acknowledged. */
    fun deliver(queue: SimulationQueue, json: String): Boolean = processor.consume(queue, json.toByteArray())

    companion object {
        val DEFAULT_PROPERTIES = SimulationConfigurationProperties(
            URI("amqp://guest:guest@localhost:5672"), URI("http://payment:8080"), URI("http://shipment:8080"), 3,
        )
    }
}
