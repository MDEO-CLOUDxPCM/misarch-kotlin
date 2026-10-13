package org.misarch.simulation.web

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.misarch.simulation.Fixture
import org.misarch.simulation.processing.SimulationQueue
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient
import java.util.UUID

/** HTTP contract of the NestJS original: status codes and exact bodies (test plan 9). */
class SimulationRoutesTest {

    private val f = Fixture(CoroutineScope(Dispatchers.Unconfined), null)
    private val client = WebTestClient.bindToRouterFunction(SimulationRoutes(f.providers, f.variables).router()).build()

    private fun post(path: String, json: String?, contentType: MediaType? = MediaType.APPLICATION_JSON): WebTestClient.ResponseSpec {
        var spec = client.post().uri(path)
        if (contentType != null) spec = spec.contentType(contentType)
        return if (json != null) spec.bodyValue(json).exchange() else spec.exchange()
    }

    private fun WebTestClient.ResponseSpec.body(): String? =
        expectBody(String::class.java).returnResult().responseBody

    @Test
    fun `payment register answers 201 with empty body, stores the record and emits the whole body`() {
        val id = UUID.randomUUID().toString()
        val body = """{"paymentId":"$id","amount":12.5,"paymentType":"prepayment","extra":{"b":null,"a":[1]}}"""
        val response = post("/payment/register", body)
        response.expectStatus().isEqualTo(201)
        assertThat(response.body()).isNull()
        assertThat(f.publisher.emitted).containsExactly(SimulationQueue.PAYMENTS to body)
        assertThat(post("/payment/findAll", null).expectStatus().isEqualTo(201).body())
            .isEqualTo("""[{"id":"$id","paymentType":"prepayment","blocked":false}]""")
    }

    @Test
    fun `validation errors are NestJS 400 bodies in property order`() {
        val response = post("/payment/register", """{"paymentId":"no-uuid","amount":"5"}""")
        response.expectStatus().isEqualTo(400)
        assertThat(response.body()).isEqualTo(
            """{"message":["paymentId must be a UUID","amount must be a number conforming to the specified constraints",""" +
                """"paymentType must be a string"],"error":"Bad Request","statusCode":400}"""
        )
        assertThat(post("/shipment/update", """{"shipmentId":"${UUID.randomUUID()}","status":5}""").body())
            .isEqualTo("""{"message":["status must be a string"],"error":"Bad Request","statusCode":400}""")
        assertThat(f.publisher.emitted).isEmpty()
    }

    @Test
    fun `non JSON content type means an empty body`() {
        val id = UUID.randomUUID()
        val response = post("/shipment/register", """{"shipmentId":"$id"}""", MediaType.TEXT_PLAIN)
        response.expectStatus().isEqualTo(400)
        assertThat(response.body()).isEqualTo("""{"message":["shipmentId must be a UUID"],"error":"Bad Request","statusCode":400}""")
        post("/shipment/register", null, null).expectStatus().isEqualTo(400)
    }

    @Test
    fun `malformed JSON is a 400 from body-parser`() {
        post("/shipment/register", """{"shipmentId":""", MediaType.APPLICATION_JSON).expectStatus().isEqualTo(400)
        post("/shipment/register", "\"string\"", MediaType.APPLICATION_JSON).expectStatus().isEqualTo(400)
    }

    @Test
    fun `body larger than 100 kB is 413`() {
        val big = """{"shipmentId":"${UUID.randomUUID()}","pad":"${"x".repeat(110 * 1024)}"}"""
        post("/shipment/register", big).expectStatus().isEqualTo(413)
    }

    @Test
    fun `shipment register and findAll`() {
        val id = UUID.randomUUID().toString()
        post("/shipment/register", """{"shipmentId":"$id","ref":"r","quantity":2,"weight":1.0,"address":{"street2":null}}""")
            .expectStatus().isEqualTo(201)
        assertThat(f.publisher.emitted.single().second)
            .isEqualTo("""{"shipmentId":"$id","ref":"r","quantity":2,"weight":1,"address":{"street2":null}}""")
        assertThat(post("/shipment/findAll", null).body()).isEqualTo("""[{"id":"$id","blocked":false}]""")
    }

    @Test
    fun `manual update of an unknown registration is 404 Payment or Shipment not found`() {
        // fix S3: payments answer "Payment not found" (the original: "Shipment not found")
        val response = post("/payment/update", """{"paymentId":"${UUID.randomUUID()}","status":"SUCCEEDED"}""")
        response.expectStatus().isEqualTo(404)
        assertThat(response.body()).isEqualTo("""{"message":"Payment not found","error":"Not Found","statusCode":404}""")
        val shipment = post("/shipment/update", """{"shipmentId":"${UUID.randomUUID()}","status":"DELIVERED"}""")
        shipment.expectStatus().isEqualTo(404)
        assertThat(shipment.body()).isEqualTo("""{"message":"Shipment not found","error":"Not Found","statusCode":404}""")
    }

    @Test
    fun `manual update blocks the registration and calls back with the given status`() {
        val id = UUID.randomUUID().toString()
        post("/shipment/register", """{"shipmentId":"$id"}""").expectStatus().isEqualTo(201)
        val response = post("/shipment/update", """{"shipmentId":"$id","status":"ANYTHING"}""")
        response.expectStatus().isEqualTo(201)
        assertThat(response.body()).isNull()
        assertThat(post("/shipment/findAll", null).body()).isEqualTo("""[{"id":"$id","blocked":true}]""")
        assertThat(f.transport.calls.map { it.uri to it.body })
            .containsExactly("http://shipment:8080/shipment/$id/status" to """{"status":"ANYTHING"}""")
    }

    @Test
    fun `ecs endpoints`() {
        val defined = client.get().uri("/ecs/defined-variables").exchange().expectStatus().isOk.body()!!
        assertThat(defined).startsWith("""{"PAYMENTS_PER_MINUTE":{"type":{"${'$'}schema":"http://json-schema.org/draft-07/schema#","type":"integer"},"defaultValue":1000000},""")
        val ok = post("/ecs/variables", """{"PAYMENT_PROCESSING_TIME":1,"PAYMENT_SUCCESS_RATE":0.5}""")
        ok.expectStatus().isEqualTo(201)
        assertThat(ok.body()).isNull()
        assertThat(f.variables.number("PAYMENT_PROCESSING_TIME")).isEqualTo(1.0)
        val failed = post("/ecs/variables", """{"SHIPMENT_PROCESSING_TIME":3,"artificialMemoryUsage":0}""")
        failed.expectStatus().isEqualTo(500)
        assertThat(failed.body()).isEqualTo("""{"statusCode":500,"message":"Internal server error"}""")
        assertThat(f.variables.number("SHIPMENT_PROCESSING_TIME")).isEqualTo(3.0)
    }

    @Test
    fun `health answers OK, also for HEAD`() {
        assertThat(client.get().uri("/health").exchange().expectStatus().isOk.body()).isEqualTo("""{"status":"OK"}""")
        client.method(HttpMethod.HEAD).uri("/health").exchange().expectStatus().isOk
    }

    @Test
    fun `unknown routes are NestJS 404s`() {
        assertThat(post("/register", """{"paymentId":"x","type":"credit-card"}""").expectStatus().isNotFound.body())
            .isEqualTo("""{"message":"Cannot POST /register","error":"Not Found","statusCode":404}""")
        assertThat(client.get().uri("/dapr/subscribe").exchange().expectStatus().isNotFound.body())
            .isEqualTo("""{"message":"Cannot GET /dapr/subscribe","error":"Not Found","statusCode":404}""")
        assertThat(client.get().uri("/payment/register?x=1").exchange().expectStatus().isNotFound.body())
            .isEqualTo("""{"message":"Cannot GET /payment/register?x=1","error":"Not Found","statusCode":404}""")
    }

    @Test
    fun `paths match like Express - case-insensitive with optional trailing slash`() {
        post("/PAYMENT/Register/", """{"paymentId":"${UUID.randomUUID()}","amount":1,"paymentType":"invoice"}""")
            .expectStatus().isEqualTo(201)
        post("/payment/registers", """{}""").expectStatus().isNotFound
    }
}
