package org.misarch.simulation

import com.rabbitmq.client.ConnectionFactory
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.misarch.simulation.amqp.RegistrationListener
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.reactive.server.WebTestClient
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * The whole application (HTTP, queues, callbacks) against a real RabbitMQ and a recording stand-in for
 * payment/shipment. The minute cron is disabled; the reset is triggered by hand.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["misarch.simulation.reset-cron=-"],
)
@AutoConfigureWebTestClient
class SimulationIntegrationTest {

    @Autowired
    lateinit var webTestClient: WebTestClient

    @Autowired
    lateinit var listener: RegistrationListener

    private fun post(path: String, json: String): Int =
        webTestClient.post().uri(path).contentType(MediaType.APPLICATION_JSON).bodyValue(json)
            .exchange().returnResult(String::class.java).status.value()

    private fun setVariables(json: String) = assertThat(post("/ecs/variables", json)).isEqualTo(200)

    private fun registrations(provider: String): String? =
        webTestClient.get().uri("/$provider/registrations").exchange().expectStatus().isOk
            .expectBody(String::class.java).returnResult().responseBody

    @BeforeEach
    fun resetCounts() = listener.resetRateLimit()

    @AfterEach
    fun defaults() {
        setVariables(
            """{"PAYMENTS_PER_MINUTE":1000000,"SHIPMENTS_PER_MINUTE":1000000,"PAYMENT_PROCESSING_TIME":5,""" +
                """"SHIPMENT_PROCESSING_TIME":5,"PAYMENT_SUCCESS_RATE":0.95,"SHIPMENT_SUCCESS_RATE":0.95}"""
        )
        callbacks.requests.clear()
    }

    @Test
    fun `payment round trip - register, queue, callback SUCCEEDED`() {
        setVariables("""{"PAYMENT_PROCESSING_TIME":0,"PAYMENT_SUCCESS_RATE":1}""")
        val id = UUID.randomUUID()
        assertThat(post("/payment/register", """{"paymentId":"$id","amount":42.5,"paymentType":"prepayment"}""")).isEqualTo(201)
        val callback = callbacks.await { it.body.contains(id.toString()) }
        assertThat(callback.method).isEqualTo("POST")
        assertThat(callback.path).isEqualTo("/payment/update-payment-status")
        assertThat(callback.contentType).startsWith("application/json")
        assertThat(callback.body).isEqualTo("""{"paymentId":"$id","status":"SUCCEEDED"}""")
    }

    @Test
    fun `shipment round trip with success rate 0 - callback FAILED after the processing time`() {
        setVariables("""{"SHIPMENT_PROCESSING_TIME":1,"SHIPMENT_SUCCESS_RATE":0}""")
        val id = UUID.randomUUID()
        val start = System.nanoTime()
        assertThat(post("/shipment/register", """{"shipmentId":"$id","ref":"dhl","quantity":1,"weight":2.5}""")).isEqualTo(201)
        val callback = callbacks.await { it.path.contains(id.toString()) }
        val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
        assertThat(callback.path).isEqualTo("/shipment/$id/status")
        assertThat(callback.body).isEqualTo("""{"status":"FAILED"}""")
        assertThat(elapsedMillis).isBetween(1000L, 3000L)
    }

    @Test
    fun `rate limit with a real broker - excess is held until the minute reset, then processed`() {
        setVariables("""{"PAYMENTS_PER_MINUTE":1,"PAYMENT_PROCESSING_TIME":0,"PAYMENT_SUCCESS_RATE":1}""")
        val (first, second) = UUID.randomUUID() to UUID.randomUUID()
        assertThat(post("/payment/register", """{"paymentId":"$first","amount":1,"paymentType":"invoice"}""")).isEqualTo(201)
        callbacks.await { it.body.contains(first.toString()) }
        assertThat(post("/payment/register", """{"paymentId":"$second","amount":1,"paymentType":"invoice"}""")).isEqualTo(201)
        assertThat(callbacks.requests.poll(2, TimeUnit.SECONDS)).isNull()
        // the held message was not consumed: its registration is still pending (manual update would work)
        assertThat(registrations("payment"))
            .isEqualTo("""[{"registration":{"paymentId":"$second","amount":1.0,"paymentType":"invoice"},"blocked":false}]""")

        listener.resetRateLimit() // what the cron does at second 0
        assertThat(callbacks.await { it.body.contains(second.toString()) }.body)
            .isEqualTo("""{"paymentId":"$second","status":"SUCCEEDED"}""")
    }

    @Test
    fun `manual update while held - one callback with the manual status`() {
        setVariables("""{"SHIPMENTS_PER_MINUTE":0,"SHIPMENT_PROCESSING_TIME":0}""")
        val id = UUID.randomUUID()
        assertThat(post("/shipment/register", """{"shipmentId":"$id"}""")).isEqualTo(201)
        Thread.sleep(300)
        assertThat(post("/shipment/update", """{"shipmentId":"$id","status":"DELIVERED"}""")).isEqualTo(200)
        assertThat(callbacks.await { it.path.contains(id.toString()) }.body).isEqualTo("""{"status":"DELIVERED"}""")
        setVariables("""{"SHIPMENTS_PER_MINUTE":10}""")
        // the queue was paused and the limit is > 0 now: the reset restarts the consumer, the held message is
        // redelivered, consumed and dropped because its registration is blocked
        listener.resetRateLimit()
        assertThat(callbacks.requests.poll(2, TimeUnit.SECONDS)).isNull()
    }

    @Test
    fun `poison messages in the queue are acked and dropped, later messages are processed`() {
        setVariables("""{"PAYMENT_PROCESSING_TIME":0,"PAYMENT_SUCCESS_RATE":1}""")
        ConnectionFactory().apply { setUri(RabbitTestBroker.url()) }.newConnection().use { connection ->
            val channel = connection.createChannel()
            for (poison in listOf("not json", "null", """{"amount":1}""", """{"paymentId":""}""")) {
                channel.basicPublish("", "payments-queue", null, poison.toByteArray())
            }
            val id = UUID.randomUUID()
            assertThat(post("/payment/register", """{"paymentId":"$id","amount":1,"paymentType":"invoice"}""")).isEqualTo(201)
            assertThat(callbacks.await { it.body.contains(id.toString()) }.body).isEqualTo("""{"paymentId":"$id","status":"SUCCEEDED"}""")
            // nothing left unacked or ready (the poison messages are gone, not redelivered)
            assertThat(channel.messageCount("payments-queue")).isZero()
        }
        assertThat(callbacks.requests).isEmpty()
    }

    @Test
    fun `backlog is delivered at once, held messages are redelivered after the reset`() {
        setVariables("""{"SHIPMENTS_PER_MINUTE":10,"SHIPMENT_PROCESSING_TIME":0,"SHIPMENT_SUCCESS_RATE":1}""")
        val ids = List(50) { UUID.randomUUID() }
        ConnectionFactory().apply { setUri(RabbitTestBroker.url()) }.newConnection().use { connection ->
            val channel = connection.createChannel()
            ids.forEach { channel.basicPublish("", "shipments-queue", null, """{"shipmentId":"$it"}""".toByteArray()) }
            val firstWindow = List(10) { callbacks.await { request -> ids.any { request.path.contains(it.toString()) } } }
            assertThat(callbacks.requests.poll(1, TimeUnit.SECONDS)).isNull()
            // no prefetch limit: the 40 held messages are unacknowledged on the consumer, none is ready in the queue
            assertThat(channel.messageCount("shipments-queue")).isZero()

            setVariables("""{"SHIPMENTS_PER_MINUTE":1000000}""")
            listener.resetRateLimit()
            val secondWindow = List(40) { callbacks.await { request -> ids.any { request.path.contains(it.toString()) } } }
            assertThat((firstWindow + secondWindow).map { it.path }.toSet())
                .containsExactlyInAnyOrderElementsOf(ids.map { "/shipment/$it/status" })
            assertThat(channel.messageCount("shipments-queue")).isZero()
        }
    }

    @Test
    fun `the consumer and the publisher recover from a closed connection`() {
        setVariables("""{"PAYMENT_PROCESSING_TIME":0,"PAYMENT_SUCCESS_RATE":1}""")
        RabbitTestBroker.closeAllConnections("/")
        val id = UUID.randomUUID()
        var status = 0
        repeat(100) {
            if (status != 201) {
                status = post("/payment/register", """{"paymentId":"$id","amount":1,"paymentType":"invoice"}""")
                if (status != 201) Thread.sleep(100)
            }
        }
        assertThat(status).isEqualTo(201)
        assertThat(callbacks.await(30) { it.body.contains(id.toString()) }.body)
            .isEqualTo("""{"paymentId":"$id","status":"SUCCEEDED"}""")
    }

    @Test
    fun `health, ECS variables and unknown routes`() {
        webTestClient.get().uri("/health").exchange().expectStatus().isOk
            .expectBody(String::class.java).isEqualTo("""{"status":"UP"}""")
        val defined = webTestClient.get().uri("/ecs/defined-variables").exchange().expectStatus().isOk
            .expectBody(String::class.java).returnResult().responseBody!!
        assertThat(Regex("\"([A-Z_]+)\":\\{").findAll(defined).map { it.groupValues[1] }.toList()).containsExactly(
            "PAYMENTS_PER_MINUTE", "SHIPMENTS_PER_MINUTE", "PAYMENT_PROCESSING_TIME", "SHIPMENT_PROCESSING_TIME",
            "PAYMENT_SUCCESS_RATE", "SHIPMENT_SUCCESS_RATE",
        )
        assertThat(post("/ecs/variables", """{"PAYMENT_PROCESSING_TIME":1,"artificialMemoryUsage":0}""")).isEqualTo(500)
        webTestClient.get().uri("/dapr/subscribe").exchange().expectStatus().isNotFound
        webTestClient.get().uri("/graphql").exchange().expectStatus().isNotFound
        webTestClient.get().uri("/").exchange().expectStatus().isNotFound
    }

    @Test
    fun `missing amount is 400, nothing is registered`() {
        val id = UUID.randomUUID()
        assertThat(post("/payment/register", """{"paymentId":"$id","paymentType":"invoice"}""")).isEqualTo(400)
        assertThat(post("/payment/register", """{"paymentId":"$id","amount":null,"paymentType":"invoice"}""")).isEqualTo(400)
        assertThat(registrations("payment")).isEqualTo("[]")
    }

    companion object {
        val callbacks = CallbackServer()

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("RABBITMQ_URL") { RabbitTestBroker.url() }
            registry.add("PAYMENT_URL") { callbacks.url }
            registry.add("SHIPMENT_URL") { callbacks.url }
        }

        @JvmStatic
        @AfterAll
        fun stopCallbacks() = callbacks.close()
    }
}
