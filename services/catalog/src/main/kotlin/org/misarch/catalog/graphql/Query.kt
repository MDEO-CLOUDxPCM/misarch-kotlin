package org.misarch.catalog.graphql

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.server.operations.Query
import graphql.schema.DataFetchingEnvironment
import org.misarch.catalog.graphql.dataloader.CategoryCharacteristicDataLoader
import org.misarch.catalog.graphql.dataloader.CategoryDataLoader
import org.misarch.catalog.graphql.dataloader.ProductDataLoader
import org.misarch.catalog.graphql.model.Category
import org.misarch.catalog.graphql.model.CategoryCharacteristic
import org.misarch.catalog.graphql.model.Product
import org.misarch.catalog.graphql.model.connection.*
import org.misarch.common.graphql.authorizedUser
import org.misarch.common.graphql.authorizedUserOrNull
import org.misarch.common.graphql.load
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import org.springframework.stereotype.Component
import java.util.*
import java.util.concurrent.CompletableFuture

/**
 * Defines GraphQL queries
 *
 * @property entityTemplate executes the connection queries (products, categories)
 */
@Component
class Query(
    private val entityTemplate: R2dbcEntityTemplate
) : Query {

    @GraphQLDescription("Get all products")
    suspend fun products(
        @GraphQLDescription("Number of items to return")
        first: Int? = null,
        @GraphQLDescription("Number of items to skip")
        skip: Int? = null,
        @GraphQLDescription("Ordering")
        orderBy: ProductOrder? = null,
        @GraphQLDescription("Filtering")
        filter: ProductFilter? = null,
        dfe: DataFetchingEnvironment
    ): ProductConnection {
        return ProductConnection(first, skip, filter, null, orderBy, entityTemplate, dfe.authorizedUserOrNull)
    }

    @GraphQLDescription("Get all categories")
    suspend fun categories(
        @GraphQLDescription("Number of items to return")
        first: Int? = null,
        @GraphQLDescription("Number of items to skip")
        skip: Int? = null,
        @GraphQLDescription("Ordering")
        orderBy: CategoryOrder? = null,
        dfe: DataFetchingEnvironment
    ): CategoryConnection {
        return CategoryConnection(first, skip, null, orderBy, entityTemplate, dfe.authorizedUserOrNull)
    }

    @GraphQLDescription("Get a product by id")
    fun product(
        @GraphQLDescription("The id of the product")
        id: UUID,
        dfe: DataFetchingEnvironment
    ): CompletableFuture<Product> {
        return dfe.load(ProductDataLoader::class, id).thenApply {
            if (!it.isPubliclyVisible) {
                dfe.authorizedUser.checkIsEmployee()
            }
            it
        }
    }

    @GraphQLDescription("Get a category by id")
    fun category(
        @GraphQLDescription("The id of the category")
        id: UUID,
        dfe: DataFetchingEnvironment
    ): CompletableFuture<Category> {
        return dfe.load(CategoryDataLoader::class, id)
    }

    @GraphQLDescription("Get a characteristic by id")
    fun categoryCharacteristic(
        @GraphQLDescription("The id of the characteristic")
        id: UUID,
        dfe: DataFetchingEnvironment
    ): CompletableFuture<CategoryCharacteristic> {
        return dfe.load(CategoryCharacteristicDataLoader::class, id)
    }

}
