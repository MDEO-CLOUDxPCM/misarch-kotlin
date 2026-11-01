package org.misarch.catalog.graphql.model

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.generator.annotations.GraphQLIgnore
import com.expediagroup.graphql.generator.federation.directives.FieldSet
import com.expediagroup.graphql.generator.federation.directives.KeyDirective
import graphql.schema.DataFetchingEnvironment
import org.misarch.catalog.graphql.dataloader.ProductDataLoader
import org.misarch.catalog.graphql.dataloader.ProductVariantVersionDataLoader
import org.misarch.catalog.graphql.model.connection.ProductVariantVersionConnection
import org.misarch.catalog.graphql.model.connection.ProductVariantVersionOrder
import org.misarch.common.graphql.Node
import org.misarch.common.graphql.authorizedUser
import org.misarch.common.graphql.authorizedUserOrNull
import org.misarch.common.graphql.load
import org.misarch.common.persistence.Condition
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import java.util.UUID
import java.util.concurrent.CompletableFuture

@GraphQLDescription("A variant of a Product.")
@KeyDirective(fields = FieldSet("id"))
class ProductVariant(
    id: UUID,
    @property:GraphQLDescription("If true, the ProductVariant is visible to customers.")
    val isPubliclyVisible: Boolean, private val productId: UUID, private val currentVersion: UUID
) : Node(id) {

    @GraphQLDescription("The Product belonging to this variant.")
    fun product(
        dfe: DataFetchingEnvironment
    ): CompletableFuture<Product> {
        return dfe.load(ProductDataLoader::class, productId)
    }

    @GraphQLDescription("The current version of the ProductVariant.")
    fun currentVersion(
        dfe: DataFetchingEnvironment
    ): CompletableFuture<ProductVariantVersion> {
        if (!this.isPubliclyVisible) {
            dfe.authorizedUser.checkIsEmployee()
        }
        return dfe.load(ProductVariantVersionDataLoader::class, currentVersion)
    }

    @GraphQLDescription("Get all associated versions")
    suspend fun versions(
        @GraphQLDescription("Number of items to return")
        first: Int? = null,
        @GraphQLDescription("Number of items to skip")
        skip: Int? = null,
        @GraphQLDescription("Ordering")
        orderBy: ProductVariantVersionOrder? = null,
        @GraphQLIgnore
        @Autowired
        entityTemplate: R2dbcEntityTemplate,
        dfe: DataFetchingEnvironment
    ): ProductVariantVersionConnection {
        dfe.authorizedUser.checkIsEmployee()
        return ProductVariantVersionConnection(
            first,
            skip,
            Condition.eq("t.productVariantId", id),
            orderBy,
            entityTemplate,
            dfe.authorizedUserOrNull
        )
    }

}
