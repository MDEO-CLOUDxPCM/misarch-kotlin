package org.misarch.simulation.payment

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.misarch.simulation.Fixture
import org.misarch.simulation.body
import org.misarch.simulation.controllerTestClient
import org.misarch.simulation.postJson
import org.misarch.simulation.processing.SimulationQueue
import org.springframework.http.MediaType
import java.util.UUID

class PaymentControllerTest {

    private val f = Fixture(CoroutineScope(Dispatchers.Unconfined), null)
    private val client = controllerTestClient(PaymentController(f.payments))

    @Test
    fun `register answers 201 with empty body, stores the payment and publishes the whole body`() {
        val id = UUID.randomUUID()
        val response = client.postJson(
            "/payment/register", """{"paymentId":"$id","amount":12.5,"paymentType":"prepayment","extra":{"b":null,"a":[1]}}"""
        )
        response.expectStatus().isCreated
        assertThat(response.body()).isNull()
        assertThat(f.publisher.emitted).containsExactly(
            SimulationQueue.PAYMENTS to
                """{"paymentId":"$id","amount":12.5,"paymentType":"prepayment","extra":{"b":null,"a":[1]}}"""
        )
        assertThat(f.payments.findAll().map { it.id }).containsExactly(id)
        assertThat(client.postJson("/payment/findAll", null).expectStatus().isCreated.body())
            .isEqualTo("""[{"id":"$id","paymentType":"prepayment","blocked":false}]""")
    }

    @Test
    fun `integral amounts are published as decimals`() {
        val id = UUID.randomUUID()
        client.postJson("/payment/register", """{"paymentId":"$id","amount":10,"paymentType":"invoice"}""")
            .expectStatus().isCreated
        assertThat(f.publisher.emitted.single().second)
            .isEqualTo("""{"paymentId":"$id","amount":10.0,"paymentType":"invoice"}""")
    }

    @Test
    fun `missing or invalid fields are 400`() {
        val id = UUID.randomUUID()
        listOf(
            """{"paymentId":"no-uuid","amount":5,"paymentType":"invoice"}""",
            """{"amount":5,"paymentType":"invoice"}""",
            """{"paymentId":"$id","paymentType":"invoice"}""",
            """{"paymentId":"$id","amount":null,"paymentType":"invoice"}""",
            """{"paymentId":"$id","amount":"x","paymentType":"invoice"}""",
            """{"paymentId":"$id","amount":5}""",
            """{"paymentId":""",
            "[]",
        ).forEach { json ->
            client.postJson("/payment/register", json).expectStatus().isBadRequest
        }
        client.postJson("/payment/register", null).expectStatus().isBadRequest
        client.postJson("/payment/update", """{"paymentId":"$id"}""").expectStatus().isBadRequest
        assertThat(f.publisher.emitted).isEmpty()
        assertThat(f.payments.findAll()).isEmpty()
    }

    @Test
    fun `scalars are coerced by Jackson`() {
        val id = UUID.randomUUID()
        client.postJson("/payment/register", """{"paymentId":"$id","amount":"5","paymentType":7}""").expectStatus().isCreated
        assertThat(f.payments.findAll().single().paymentType).isEqualTo("7")
        assertThat(f.publisher.emitted.single().second).isEqualTo("""{"paymentId":"$id","amount":5.0,"paymentType":"7"}""")
    }

    @Test
    fun `non JSON content type is 415`() {
        client.post().uri("/payment/register").contentType(MediaType.TEXT_PLAIN)
            .bodyValue("""{"paymentId":"${UUID.randomUUID()}","amount":1,"paymentType":"invoice"}""")
            .exchange().expectStatus().isEqualTo(415)
    }

    @Test
    fun `manual update of an unknown payment is 404`() {
        client.postJson("/payment/update", """{"paymentId":"${UUID.randomUUID()}","status":"SUCCEEDED"}""")
            .expectStatus().isNotFound
        assertThat(f.transport.calls).isEmpty()
    }

    @Test
    fun `manual update blocks the payment and calls back with the given status`() {
        val id = UUID.randomUUID()
        client.postJson("/payment/register", """{"paymentId":"$id","amount":1,"paymentType":"invoice"}""")
            .expectStatus().isCreated
        val response = client.postJson("/payment/update", """{"paymentId":"$id","status":"INKASSO"}""")
        response.expectStatus().isCreated
        assertThat(response.body()).isNull()
        assertThat(client.postJson("/payment/findAll", null).body())
            .isEqualTo("""[{"id":"$id","paymentType":"invoice","blocked":true}]""")
        assertThat(f.transport.calls.map { it.uri to it.body }).containsExactly(
            "http://payment:8080/payment/update-payment-status" to """{"paymentId":"$id","status":"INKASSO"}"""
        )
    }

    @Test
    fun `unknown routes are 404`() {
        client.postJson("/register", """{"paymentId":"x","type":"credit-card"}""").expectStatus().isNotFound
        client.get().uri("/payment/register").exchange().expectStatus().isEqualTo(405)
    }
}
