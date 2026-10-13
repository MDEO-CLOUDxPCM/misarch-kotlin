package org.misarch.order

import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.misarch.common.event.DaprProperties
import org.misarch.common.graphql.AuthorizedUser
import org.misarch.order.event.OrderEventPublisher
import org.misarch.order.persistence.model.OrderCompensationRecord
import org.misarch.order.remote.RemoteServices
import org.springframework.web.reactive.function.client.WebClient
import java.time.Instant
import java.util.UUID

/**
 * An unreachable sidecar fails like reqwest: `error sending request for url (<url>)`.
 */
class TransportFailureTest {

    private val properties = DaprProperties(httpEndpoint = "http://127.0.0.1:1")

    @Test
    fun `publish to an unreachable sidecar`() {
        val publisher = OrderEventPublisher(WebClient.create(), properties)
        val compensation = OrderCompensationRecord(UUID.randomUUID(), UUID.randomUUID(), emptyList(), Instant.now(), 1)
        assertThatThrownBy { runBlocking { publisher.publishOrderCompensationCreated(compensation) } }
            .hasMessage("error sending request for url (http://127.0.0.1:1/v1.0/publish/pubsub/order/order-compensation/created)")
    }

    @Test
    fun `invocation of an unreachable sidecar`() {
        val remote = RemoteServices(WebClient.create(), properties)
        assertThatThrownBy { runBlocking { remote.shoppingCartLines(AuthorizedUser(UUID.randomUUID(), listOf("buyer")), UUID.randomUUID()) } }
            .hasMessage("error sending request for url (http://127.0.0.1:1/v1.0/invoke/shoppingcart/method/)")
        assertThatThrownBy { runBlocking { remote.shipmentFees(emptyList()) } }
            .hasMessage("error sending request for url (http://127.0.0.1:1/v1.0/invoke/shipment/method/graphql)")
    }
}
