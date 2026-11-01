package org.misarch.inventory.graphql

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.server.operations.Mutation
import graphql.schema.DataFetchingEnvironment
import org.misarch.common.graphql.authorizedUser
import org.misarch.inventory.graphql.input.CreateProductItemBatchInput
import org.misarch.inventory.graphql.input.ReserveProductItemsBatchInput
import org.misarch.inventory.graphql.input.UpdateProductItemInput
import org.misarch.inventory.graphql.model.ProductItem
import org.misarch.inventory.service.ProductItemService
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Defines GraphQL mutations
 *
 * @property productItemService service for product items
 */
@Component
class Mutation(
    private val productItemService: ProductItemService
) : Mutation {

    @GraphQLDescription("Adds a batch of product items with the specified productVartiantId of size number")
    suspend fun createProductItemBatch(input: CreateProductItemBatchInput, dfe: DataFetchingEnvironment): List<ProductItem> {
        dfe.authorizedUser.checkIsEmployee()
        return productItemService.createProductItemBatch(input).map { it.toDTO() }
    }

    @GraphQLDescription("Updates storage state, productVariant of a specific product item referenced with an Id")
    suspend fun updateProductItem(input: UpdateProductItemInput, dfe: DataFetchingEnvironment): ProductItem {
        dfe.authorizedUser.checkIsEmployee()
        return productItemService.updateProductItem(input).toDTO()
    }

    @GraphQLDescription("Deletes a product item by id")
    suspend fun deleteProductItem(
        @GraphQLDescription("UUID of product item to delete")
        id: UUID,
        dfe: DataFetchingEnvironment
    ): ProductItem {
        dfe.authorizedUser.checkIsAdmin()
        return productItemService.deleteProductItem(id).toDTO()
    }

    @GraphQLDescription("Reserves a batch of product items of a chosen product variant")
    suspend fun reserveProductItemBatch(
        input: ReserveProductItemsBatchInput, dfe: DataFetchingEnvironment
    ): List<ProductItem> {
        dfe.authorizedUser.checkIsEmployee()
        return productItemService.reserveProductItemBatch(input).map { it.toDTO() }
    }

}
