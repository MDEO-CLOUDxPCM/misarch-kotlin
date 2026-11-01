package org.misarch.shipment.service

import kotlinx.coroutines.flow.toList
import org.misarch.common.event.EventPublisher
import org.misarch.common.persistence.findByIdOrThrow
import org.misarch.shipment.event.ShipmentEvents
import org.misarch.shipment.event.model.ArchiveShipmentMethodDTO
import org.misarch.shipment.graphql.input.ArchiveShipmentMethodInput
import org.misarch.shipment.graphql.input.CalculateShipmentFeesInput
import org.misarch.shipment.graphql.input.CreateShipmentMethodInput
import org.misarch.shipment.graphql.input.ProductVariantVersionWithQuantityInput
import org.misarch.shipment.persistence.model.OrderItemEntity
import org.misarch.shipment.persistence.model.ShipmentMethodEntity
import org.misarch.shipment.persistence.repository.ProductVariantVersionRepository
import org.misarch.shipment.persistence.repository.ShipmentMethodRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime

/**
 * Service for [ShipmentMethodEntity]s
 *
 * @param repository the provided repository
 * @param productVariantVersionRepository the product variant version repository
 * @param eventPublisher the event publisher
 */
@Service
class ShipmentMethodService(
    private val repository: ShipmentMethodRepository,
    private val productVariantVersionRepository: ProductVariantVersionRepository,
    private val eventPublisher: EventPublisher
) {

    /**
     * Creates a shipment method
     *
     * @param shipmentMethodInput the shipment method to create
     * @return the created shipment method
     */
    @Transactional
    suspend fun createShipmentMethod(shipmentMethodInput: CreateShipmentMethodInput): ShipmentMethodEntity {
        val shipmentMethod = ShipmentMethodEntity(
            name = shipmentMethodInput.name,
            description = shipmentMethodInput.description,
            baseFees = shipmentMethodInput.baseFees,
            feesPerItem = shipmentMethodInput.feesPerItem,
            feesPerKg = shipmentMethodInput.feesPerKg,
            externalReference = shipmentMethodInput.externalReference,
            id = null,
            archivedAt = null
        )
        val savedShipmentMethod = repository.save(shipmentMethod)
        eventPublisher.publishEvent(ShipmentEvents.SHIPMENT_METHOD_CREATED, savedShipmentMethod.toEventDTO())
        return savedShipmentMethod
    }

    /**
     * Archives a shipment method (also an already archived one: `archivedAt` is overwritten, the event is published
     * again - like the original)
     *
     * @param archiveShipmentMethodInput defines the shipment method to archive
     * @return the archived shipment method
     */
    @Transactional
    suspend fun archiveShipmentMethod(archiveShipmentMethodInput: ArchiveShipmentMethodInput): ShipmentMethodEntity {
        // original: findById(...).awaitSingle() -> NoSuchElementException without message for an unknown id
        val shipmentMethod = repository.findByIdOrThrow(archiveShipmentMethodInput.id)
        shipmentMethod.archivedAt = OffsetDateTime.now()
        val savedShipmentMethod = repository.save(shipmentMethod)
        eventPublisher.publishEvent(
            ShipmentEvents.SHIPMENT_METHOD_ARCHIVED, ArchiveShipmentMethodDTO(shipmentMethod.id!!)
        )
        return savedShipmentMethod
    }

    /**
     * Calculates the shipment fees for a collection of items. Archived shipment methods are rejected like unknown ones
     * (fix: "If true, this shipment method is archived and can no longer be used." - the original accepted them, so
     * order could create orders with an archived method)
     *
     * @param input the input for the calculation
     * @return the calculated shipment fees
     */
    suspend fun calculateShipmentFees(input: CalculateShipmentFeesInput): Int {
        val groupedProductVariantWithQuantity = input.items.groupBy { it.shipmentMethodId }
        val shipmentMethodsById =
            repository.findAllById(groupedProductVariantWithQuantity.keys).toList().associateBy { it.id }
        require(groupedProductVariantWithQuantity.keys.all { shipmentMethodsById[it]?.archivedAt == null && it in shipmentMethodsById }) {
            "Not all shipment methods are valid"
        }
        return groupedProductVariantWithQuantity.map { (shipmentMethodId, items) ->
            val shipmentMethod = shipmentMethodsById.getValue(shipmentMethodId)
            val (quantity, weight) = calculateQuantityAndWeight(items)
            calculateFees(shipmentMethod, quantity, weight)
        }.sum()
    }

    /**
     * Finds the least expensive shipment method for a collection of items among the methods that are not archived
     * (fix: the original also picked archived methods, which "can no longer be used")
     *
     * @param items the items to find the least expensive shipment method for
     * @return the least expensive shipment method
     */
    suspend fun findLeastExpensiveShipmentMethod(items: List<OrderItemEntity>): ShipmentMethodEntity {
        val shipmentMethods = repository.findAll().toList().filter { it.archivedAt == null }
        require(shipmentMethods.isNotEmpty()) { "No shipment methods available" }
        val (quantity, weight) = calculateQuantityAndWeight(items.map {
            ProductVariantVersionWithQuantityInput(
                it.productVariantVersionId, it.quantity
            )
        })
        return shipmentMethods.minBy { calculateFees(it, quantity, weight) }
    }

    /**
     * Calculates the quantity and weight of a (potential) shipment
     *
     * @param items the items to calculate the quantity and weight for
     * @return the quantity and weight of the items
     */
    suspend fun calculateQuantityAndWeight(
        items: List<ProductVariantVersionWithQuantityInput>,
    ): QuantityAndWeight {
        require(items.all { it.quantity > 0 }) { "Quantity must be greater than 0" }
        val productVariantVersions =
            productVariantVersionRepository.findAllById(items.map { it.productVariantVersionId }).toList()
                .associateBy { it.id }
        require(items.all { it.productVariantVersionId in productVariantVersions }) {
            "Not all product variant versions are valid"
        }
        val weight = items.sumOf { productVariantVersions.getValue(it.productVariantVersionId).weight * it.quantity }
        val totalQuantity = items.sumOf { it.quantity }
        return QuantityAndWeight(totalQuantity, weight)
    }

    companion object {
        /**
         * Calculates the fees for a shipment (Int arithmetic, overflow wraps; the weight part is truncated)
         *
         * @param shipmentMethod the shipment method to calculate the fees for
         * @param quantity the quantity of items
         * @param weight the weight of the items
         */
        fun calculateFees(
            shipmentMethod: ShipmentMethodEntity, quantity: Int, weight: Double
        ): Int {
            return shipmentMethod.run {
                baseFees + (quantity * feesPerItem) + (weight * feesPerKg).toInt()
            }
        }
    }

}

/**
 * Quantity of items and weight of a (potential) shipment
 *
 * @property quantity the quantity of items
 * @property weight the weight of the items
 */
data class QuantityAndWeight(
    val quantity: Int, val weight: Double
)
