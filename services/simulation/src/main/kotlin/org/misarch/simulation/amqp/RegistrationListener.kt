package org.misarch.simulation.amqp

import com.rabbitmq.client.Channel
import org.misarch.simulation.model.PAYMENTS_QUEUE
import org.misarch.simulation.model.SHIPMENTS_QUEUE
import org.misarch.simulation.model.SimulationQueue
import org.misarch.simulation.service.ProcessingService
import org.springframework.amqp.core.Message
import org.springframework.amqp.rabbit.annotation.RabbitListener
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Consumer of both registration queues (manual acknowledgement, no prefetch limit). A message held by the rate
 * limit stays unacknowledged; at the minute reset the consumer of a queue that may process again is restarted, its
 * channel closes and the broker redelivers the held messages.
 */
@Component
class RegistrationListener(
    private val processingService: ProcessingService,
    private val listenerRegistry: RabbitListenerEndpointRegistry,
) {

    /**
     * Consumes a payment registration
     *
     * @param message the queued registration
     * @param channel the channel the message was delivered on
     */
    @RabbitListener(id = PAYMENTS_QUEUE, queues = [PAYMENTS_QUEUE])
    fun onPaymentRegistration(message: Message, channel: Channel) = consume(SimulationQueue.PAYMENTS, message, channel)

    /**
     * Consumes a shipment registration
     *
     * @param message the queued registration
     * @param channel the channel the message was delivered on
     */
    @RabbitListener(id = SHIPMENTS_QUEUE, queues = [SHIPMENTS_QUEUE])
    fun onShipmentRegistration(message: Message, channel: Channel) = consume(SimulationQueue.SHIPMENTS, message, channel)

    /**
     * Rate-limit reset at second 0 of every minute; restarts the consumers of the queues that held messages.
     */
    @Scheduled(cron = "\${misarch.simulation.reset-cron}")
    fun resetRateLimit() {
        for (queue in processingService.resetMessageCounts()) {
            val container = checkNotNull(listenerRegistry.getListenerContainer(queue.queueName))
            container.stop()
            container.start()
        }
    }

    private fun consume(queue: SimulationQueue, message: Message, channel: Channel) {
        if (processingService.consume(queue, message.body)) {
            channel.basicAck(message.messageProperties.deliveryTag, false)
        }
    }

}
