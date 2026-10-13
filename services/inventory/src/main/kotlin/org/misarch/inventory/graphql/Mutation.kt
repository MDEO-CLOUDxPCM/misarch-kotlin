package org.misarch.inventory.graphql

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.server.operations.Mutation
import graphql.schema.DataFetchingEnvironment
import org.misarch.inventory.graphql.input.CreateProductItemBatchInput
import org.misarch.inventory.graphql.input.ReserveProductItemsBatchInput
import org.misarch.inventory.graphql.input.UpdateProductItemInput
import org.misarch.inventory.graphql.model.ProductItem
import org.misarch.common.nest.NotFoundException
import org.misarch.common.nest.RolesGuard
import org.misarch.common.nest.Uuid
import org.misarch.inventory.service.InventoryService
import org.springframework.stereotype.Component

/**
 * Mutations of the original `InventoryResolver` (docs/specs/inventory.md §4.2). No events are published.
 */
@Component
class Mutation(private val inventoryService: InventoryService) : Mutation {

    @GraphQLDescription("Adds a batch of product items with the specified productVartiantId of size number")
    suspend fun createProductItemBatch(input: CreateProductItemBatchInput, dfe: DataFetchingEnvironment): List<ProductItem> {
        RolesGuard.check(dfe, RolesGuard.EMPLOYEE, RolesGuard.ADMIN)
        return inventoryService.createProductItemBatch(input.productVariantId.value, input.number).map { ProductItem(it) }
    }

    @GraphQLDescription("Updates storage state, productVariant of a specific product item referenced with an Id")
    suspend fun updateProductItem(input: UpdateProductItemInput, dfe: DataFetchingEnvironment): ProductItem {
        RolesGuard.check(dfe, RolesGuard.EMPLOYEE, RolesGuard.ADMIN)
        return ProductItem(inventoryService.update(input.id.value, input.productVariantId.value, input.inventoryStatus.name))
    }

    @GraphQLDescription("Deletes a product item by id")
    suspend fun deleteProductItem(
        @GraphQLDescription("UUID of product item to delete")
        id: Uuid,
        dfe: DataFetchingEnvironment,
    ): ProductItem {
        RolesGuard.check(dfe, RolesGuard.ADMIN)
        // FIXED (docs/fixes/inventory.md): a missing item is the same not-found error as productItem/updateProductItem
        // (the original returned null for the non-null field: "Cannot return null…", data null)
        val deleted = inventoryService.delete(id.value)
            ?: throw NotFoundException("ProductItem with ID \"${id.value}\" not found")
        return ProductItem(deleted)
    }

    @GraphQLDescription("Reserves a batch of product items of a chosen product variant")
    suspend fun reserveProductItemBatch(input: ReserveProductItemsBatchInput, dfe: DataFetchingEnvironment): List<ProductItem> {
        RolesGuard.check(dfe, RolesGuard.EMPLOYEE, RolesGuard.ADMIN)
        // FIXED: returns the reserved items (the original's reload query matched nothing, so it always returned [])
        return inventoryService.reserveProductItemBatch(input.productVariantId.value, input.number.toLong(), input.orderId.value)
            .map { ProductItem(it) }
    }
}
