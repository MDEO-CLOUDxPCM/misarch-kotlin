package org.misarch.simulation

import com.rabbitmq.client.ConnectionFactory
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.misarch.simulation.processing.EventProcessor
import org.misarch.simulation.processing.FatalErrorHandler
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.reactive.server.WebTestClient
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * The whole application (HTTP + RabbitMQ consumer/publishers + callbacks) against a real RabbitMQ and a recording
 * stand-in for payment/shipment (test plan 10-12). The minute cron is disabled; the reset is triggered by hand.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["misarch.simulation.reset-cron=-"],
)
@AutoConfigureWebTestClient
@Import(SimulationIntegrationTest.TestBeans::class)
class SimulationIntegrationTest {

    @TestConfiguration
    class TestBeans {
        @Bean
        @Primary
        fun recordingFatalErrorHandler(): FatalErrorHandler = fatal
    }

    @Autowired
    lateinit var webTestClient: WebTestClient

    @Autowired
    lateinit var processor: EventProcessor

    private fun post(path: String, json: String): Int =
        webTestClient.post().uri(path).contentType(MediaType.APPLICATION_JSON).bodyValue(json)
            .exchange().returnResult(String::class.java).status.value()

    private fun setVariables(json: String) = assertThat(post("/ecs/variables", json)).isEqualTo(200)

    @BeforeEach
    fun resetCounts() = processor.resetMessageCounts()

    @AfterEach
    fun defaults() {
        setVariables(
            """{"PAYMENTS_PER_MINUTE":1000000,"SHIPMENTS_PER_MINUTE":1000000,"PAYMENT_PROCESSING_TIME":5,""" +
                """"SHIPMENT_PROCESSING_TIME":5,"PAYMENT_SUCCESS_RATE":0.95,"SHIPMENT_SUCCESS_RATE":0.95}"""
        )
        callbacks.requests.clear()
        assertThat(fatal.messages).isEmpty()
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
        // the held message was not consumed: its registration is still in memory (manual update would work)
        val pending = webTestClient.post().uri("/payment/findAll").exchange().expectStatus().isEqualTo(201)
            .expectBody(String::class.java).returnResult().responseBody
        assertThat(pending).isEqualTo("""[{"id":"$second","paymentType":"invoice","blocked":false}]""")

        processor.resetMessageCounts() // what the cron does at second 0
        assertThat(callbacks.await { it.body.contains(second.toString()) }.body)
            .isEqualTo("""{"paymentId":"$second","status":"SUCCEEDED"}""")
    }

    @Test
    fun `manual update while held - one callback with the manual status`() {
        setVariables("""{"SHIPMENTS_PER_MINUTE":0,"SHIPMENT_PROCESSING_TIME":0}""")
        val id = UUID.randomUUID()
        assertThat(post("/shipment/register", """{"shipmentId":"$id"}""")).isEqualTo(201)
        Thread.sleep(300)
        assertThat(post("/shipment/update", """{"shipmentId":"$id","status":"DELIVERED"}""")).isEqualTo(201)
        assertThat(callbacks.await { it.path.contains(id.toString()) }.body).isEqualTo("""{"status":"DELIVERED"}""")
        setVariables("""{"SHIPMENTS_PER_MINUTE":10}""")
        // the queue was paused and the limit is > 0 now: the reset reconnects, the held message is redelivered,
        // consumed and dropped because its registration is blocked
        processor.resetMessageCounts()
        assertThat(callbacks.requests.poll(2, TimeUnit.SECONDS)).isNull()
    }

    @Test
    fun `poison messages in the queue are acked and dropped, later messages are processed (fix S1)`() {
        setVariables("""{"PAYMENT_PROCESSING_TIME":0,"PAYMENT_SUCCESS_RATE":1}""")
        ConnectionFactory().apply { setUri(RabbitTestBroker.url()) }.newConnection().use { connection ->
            val channel = connection.createChannel()
            for (poison in listOf("not json", "null", """{"data":{"amount":1}}""", """{"pattern":"register-payment"}""")) {
                channel.basicPublish("", "payments-queue", null, poison.toByteArray())
            }
            val id = UUID.randomUUID()
            assertThat(post("/payment/register", """{"paymentId":"$id","amount":1,"paymentType":"invoice"}""")).isEqualTo(201)
            assertThat(callbacks.await { it.body.contains(id.toString()) }.body).isEqualTo("""{"paymentId":"$id","status":"SUCCEEDED"}""")
            // nothing left unacked or ready (the poison messages are gone, not redelivered)
            assertThat(channel.messageCount("payments-queue")).isZero()
        }
        assertThat(callbacks.requests).isEmpty()
        assertThat(fatal.messages).isEmpty()
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
        val pending = webTestClient.post().uri("/payment/findAll").exchange().expectStatus().isEqualTo(201)
            .expectBody(String::class.java).returnResult().responseBody
        assertThat(pending).isEqualTo("[]")
    }

    companion object {
        val fatal = RecordingFatalHandler()
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
