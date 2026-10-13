package org.misarch.inventory.graphql

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.server.operations.Query
import graphql.schema.DataFetchingEnvironment
import org.misarch.common.graphql.GraphQLDefaultValue
import org.misarch.inventory.graphql.input.ProductItemFilter
import org.misarch.inventory.graphql.input.ProductItemOrder
import org.misarch.inventory.graphql.model.ProductItem
import org.misarch.inventory.graphql.model.ProductItemConnection
import org.misarch.common.nest.RolesGuard
import org.misarch.common.nest.Uuid
import org.misarch.inventory.nest.queryKeys
import org.misarch.inventory.service.InventoryService
import org.springframework.stereotype.Component

/**
 * Queries of the original `InventoryResolver` (docs/specs/inventory.md §4.2).
 */
@Component
class Query(private val inventoryService: InventoryService) : Query {

    @GraphQLDescription("Retrieves all product items matching the filter")
    suspend fun productItems(
        @GraphQLDescription("Number of items to skip")
        @GraphQLDefaultValue("0")
        skip: Int? = 0,
        @GraphQLDescription("Number of items to return")
        @GraphQLDefaultValue("2147483647")
        first: Int? = Int.MAX_VALUE,
        @GraphQLDescription("Ordering")
        orderBy: ProductItemOrder? = null,
        @GraphQLDescription("Filtering")
        filter: ProductItemFilter? = null,
        dfe: DataFetchingEnvironment,
    ): ProductItemConnection {
        RolesGuard.check(dfe, RolesGuard.EMPLOYEE, RolesGuard.ADMIN)
        val args = ConnectionArgs(dfe)
        args.validate()
        return ProductItemConnection.build(
            dfe.queryKeys(), args,
            nodes = {
                // quirk: buildQuery maps productVariant -> `status`, inventoryStatus -> `createdAt` (fields that do
                // not exist), so any set filter field yields no nodes
                if (args.filterTruthy("productVariant") || args.filterTruthy("inventoryStatus")) emptyList()
                else inventoryService.findAll(args.ascending, args.first!!, args.skip!!)
            },
            count = { inventoryService.count(args.filterValue("productVariant"), args.filterValue("inventoryStatus")) },
        )
    }

    @GraphQLDescription("Retrieves a product item by id")
    suspend fun productItem(
        @GraphQLDescription("UUID of the user")
        id: Uuid,
        dfe: DataFetchingEnvironment,
    ): ProductItem {
        RolesGuard.check(dfe, RolesGuard.EMPLOYEE, RolesGuard.ADMIN)
        return ProductItem(inventoryService.findById(id.value))
    }
}
