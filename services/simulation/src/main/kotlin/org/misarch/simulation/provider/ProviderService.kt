package org.misarch.simulation.provider

import org.misarch.simulation.connector.ConnectorService
import org.misarch.simulation.js.JsJson
import org.misarch.simulation.processing.ProviderRecord
import org.misarch.simulation.processing.RegistrationPublisher
import org.misarch.simulation.processing.SimulationQueue
import org.misarch.simulation.processing.SimulationState
import org.slf4j.LoggerFactory
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ArrayNode

/**
 * `NotFoundException('<Payment|Shipment> not found')`. The original answered "Shipment not found" for payments too
 * (copy-paste, quirk Q5; fixed: S3 in docs/fixes/simulation.md).
 */
class ProviderRecordNotFoundException(entity: String) : RuntimeException("$entity not found")

/**
 * Port of the original `PaymentService` and `ShipmentService` (register, manual update, findAll). The request
 * bodies are validated by the caller ([org.misarch.simulation.web.Validation]).
 */
class ProviderService(
    private val state: SimulationState,
    private val publisher: RegistrationPublisher,
    private val connector: ConnectorService,
) {
    private val log = LoggerFactory.getLogger(ProviderService::class.java)

    /**
     * `register(data)`: stores `{id, paymentType?, blocked: false}` and emits `register-payment|shipment` with the
     * whole (validated) body. The emit is asynchronous in the original, so the record is always stored first.
     */
    fun register(queue: SimulationQueue, data: JsonNode) {
        val id = data.get(queue.idField).stringValue()
        when (queue) {
            SimulationQueue.PAYMENTS -> log.info("Registering payment")
            SimulationQueue.SHIPMENTS -> log.info("Registering shipment: {}}", JsJson.stringify(data))
        }
        val paymentType = if (queue == SimulationQueue.PAYMENTS) data.get("paymentType").stringValue() else null
        state.locked { repository(queue).create(ProviderRecord(id, paymentType, blocked = false)) }
        publisher.emit(queue, data)
    }

    /**
     * `update(data)`: blocks the (first) registration for automatic processing and sends the given status right away
     * (fire-and-forget, with retries); 404 "Payment not found" / "Shipment not found" if the registration is not (or
     * no longer) in memory.
     */
    fun update(queue: SimulationQueue, id: String, status: String) {
        val entity = if (queue == SimulationQueue.PAYMENTS) "Payment" else "Shipment"
        log.info("Manually updating {}: {} -> {}", entity.lowercase(), id, status)
        val record = state.locked { repository(queue).findById(id)?.also { it.blocked = true } }
        if (record == null) {
            log.error("{} not found: {}", entity, id)
            throw ProviderRecordNotFoundException(entity)
        }
        when (queue) {
            SimulationQueue.PAYMENTS -> connector.sendUpdateToPayment(id, status)
            SimulationQueue.SHIPMENTS -> connector.sendUpdateToShipment(id, status)
        }
    }

    /** `findAll()`: registrations whose queue message has not been consumed yet. */
    fun findAll(queue: SimulationQueue): ArrayNode {
        val records = state.locked { repository(queue).findAll().map { Triple(it.id, it.paymentType, it.blocked) } }
        return JsJson.arrayNode().apply {
            records.forEach { (id, paymentType, blocked) ->
                addObject().apply {
                    put("id", id)
                    if (queue == SimulationQueue.PAYMENTS) put("paymentType", paymentType)
                    put("blocked", blocked)
                }
            }
        }
    }
}
