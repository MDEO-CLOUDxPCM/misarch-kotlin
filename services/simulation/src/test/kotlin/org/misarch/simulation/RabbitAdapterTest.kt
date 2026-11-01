package org.misarch.simulation

import com.rabbitmq.client.ConnectionFactory
import com.rabbitmq.client.GetResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.misarch.simulation.model.RegisterPaymentDTO
import org.misarch.simulation.model.SimulationQueue
import org.misarch.simulation.service.RegistrationService
import org.springframework.amqp.core.Queue
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory
import org.springframework.amqp.rabbit.core.RabbitAdmin
import org.springframework.amqp.rabbit.core.RabbitTemplate
import java.io.IOException
import java.net.URI
import java.util.UUID

/** The published registration messages against a real RabbitMQ, in an own vhost. */
class RabbitAdapterTest {

    @Test
    fun `a registration is published as received, transient, to the durable queue`() {
        val url = RabbitTestBroker.vhost("wire")
        val connectionFactory = CachingConnectionFactory(URI(url))
        try {
            RabbitAdmin(connectionFactory).declareQueue(Queue(SimulationQueue.PAYMENTS.queueName))
            val f = Fixture(CoroutineScope(Dispatchers.Unconfined), null)
            val service = RegistrationService<RegisterPaymentDTO>(
                SimulationQueue.PAYMENTS, RabbitTemplate(connectionFactory), testJsonMapper, f.callbackService
            )
            service.register(RegisterPaymentDTO(UUID.fromString("a8a1f5f2-6b2e-4c1e-9d5c-0d1e2f3a4b5c"), 10.0, "invoice"))

            ConnectionFactory().apply { setUri(url) }.newConnection().use { connection ->
                val channel = connection.createChannel()
                var response: GetResponse? = null
                repeat(100) {
                    response = response ?: channel.basicGet("payments-queue", true)
                    if (response == null) Thread.sleep(100)
                }
                val message = response ?: error("nothing published")
                assertThat(String(message.body)).isEqualTo(
                    """{"paymentId":"a8a1f5f2-6b2e-4c1e-9d5c-0d1e2f3a4b5c","amount":10.0,"paymentType":"invoice"}"""
                )
                assertThat(message.props.deliveryMode).isEqualTo(1)
                assertThat(message.props.contentType).isEqualTo("application/json")
                // the queue is durable: redeclaring it non-durable fails (PRECONDITION_FAILED)
                assertThatThrownBy { connection.createChannel().queueDeclare("payments-queue", false, false, false, null) }
                    .isInstanceOf(IOException::class.java)
            }
        } finally {
            connectionFactory.destroy()
        }
    }
}
