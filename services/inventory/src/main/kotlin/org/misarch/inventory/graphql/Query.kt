package org.misarch.inventory.graphql

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.generator.annotations.GraphQLIgnore
import com.expediagroup.graphql.server.operations.Query
import graphql.schema.DataFetchingEnvironment
import org.misarch.common.graphql.GraphQLDefaultValue
import org.misarch.common.graphql.authorizedUser
import org.misarch.common.graphql.load
import org.misarch.inventory.graphql.dataloader.ProductItemDataLoader
import org.misarch.inventory.graphql.model.ProductItem
import org.misarch.inventory.graphql.model.connection.ProductItemConnection
import org.misarch.inventory.graphql.model.connection.ProductItemFilter
import org.misarch.inventory.graphql.model.connection.ProductItemOrder
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import org.springframework.stereotype.Component
import java.util.UUID
import java.util.concurrent.CompletableFuture

/**
 * Defines GraphQL queries
 */
@Component
class Query : Query {

    @GraphQLDescription("Retrieves all product items matching the filter")
    fun productItems(
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
        @GraphQLIgnore
        @Autowired
        entityTemplate: R2dbcEntityTemplate,
        dfe: DataFetchingEnvironment
    ): ProductItemConnection {
        dfe.authorizedUser.checkIsEmployee()
        return ProductItemConnection(first, skip, filter, null, orderBy, entityTemplate)
    }

    @GraphQLDescription("Retrieves a product item by id")
    fun productItem(
        @GraphQLDescription("UUID of the user")
        id: UUID,
        dfe: DataFetchingEnvironment
    ): CompletableFuture<ProductItem> {
        dfe.authorizedUser.checkIsEmployee()
        return dfe.load(ProductItemDataLoader::class, id)
    }

}
