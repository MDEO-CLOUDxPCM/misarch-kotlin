package org.misarch.simulation.shipment

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.misarch.simulation.Fixture
import org.misarch.simulation.body
import org.misarch.simulation.controllerTestClient
import org.misarch.simulation.postJson
import org.misarch.simulation.processing.SimulationQueue
import org.misarch.simulation.testJsonMapper
import java.util.UUID

class ShipmentControllerTest {

    private val f = Fixture(CoroutineScope(Dispatchers.Unconfined), null)
    private val client = controllerTestClient(ShipmentController(f.shipments))

    @Test
    fun `register keeps the further properties in the published message`() {
        val id = UUID.randomUUID()
        val body = """{"shipmentId":"$id","ref":"r","quantity":2,"weight":1.5,"address":{"street2":null,"city":"S"}}"""
        val response = client.postJson("/shipment/register", body)
        response.expectStatus().isCreated
        assertThat(response.body()).isNull()
        // the further properties are relayed (Jackson does not keep their key order)
        val (queue, published) = f.publisher.emitted.single()
        assertThat(queue).isEqualTo(SimulationQueue.SHIPMENTS)
        assertThat(testJsonMapper.readTree(published)).isEqualTo(testJsonMapper.readTree(body))
        assertThat(published).startsWith("""{"shipmentId":"$id",""")
        assertThat(client.postJson("/shipment/findAll", null).expectStatus().isCreated.body())
            .isEqualTo("""[{"id":"$id","blocked":false}]""")
    }

    @Test
    fun `missing or invalid shipment ids are 400`() {
        listOf("""{}""", """{"shipmentId":"no-uuid"}""", """{"shipmentId":5}""").forEach { json ->
            client.postJson("/shipment/register", json).expectStatus().isBadRequest
        }
        client.postJson("/shipment/update", """{"shipmentId":"${UUID.randomUUID()}"}""").expectStatus().isBadRequest
        assertThat(f.publisher.emitted).isEmpty()
    }

    @Test
    fun `manual update of an unknown shipment is 404`() {
        client.postJson("/shipment/update", """{"shipmentId":"${UUID.randomUUID()}","status":"DELIVERED"}""")
            .expectStatus().isNotFound
    }

    @Test
    fun `manual update blocks the shipment and calls back with the given status`() {
        val id = UUID.randomUUID()
        client.postJson("/shipment/register", """{"shipmentId":"$id"}""").expectStatus().isCreated
        client.postJson("/shipment/update", """{"shipmentId":"$id","status":"ANYTHING"}""").expectStatus().isCreated
        assertThat(client.postJson("/shipment/findAll", null).body()).isEqualTo("""[{"id":"$id","blocked":true}]""")
        assertThat(f.transport.calls.map { it.uri to it.body })
            .containsExactly("http://shipment:8080/shipment/$id/status" to """{"status":"ANYTHING"}""")
    }
}
