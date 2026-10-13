package org.misarch.catalog.graphql.model

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.generator.annotations.GraphQLIgnore
import com.expediagroup.graphql.generator.federation.directives.FieldSet
import com.expediagroup.graphql.generator.federation.directives.KeyDirective
import graphql.schema.DataFetchingEnvironment
import org.misarch.catalog.graphql.dataloader.ProductVariantDataLoader
import org.misarch.catalog.graphql.model.connection.*
import org.misarch.common.graphql.Node
import org.misarch.common.graphql.authorizedUser
import org.misarch.common.graphql.authorizedUserOrNull
import org.misarch.common.graphql.dataLoader
import org.misarch.common.persistence.Condition
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import java.util.*
import java.util.concurrent.CompletableFuture

@GraphQLDescription("A product.")
@KeyDirective(fields = FieldSet("id"))
class Product(
    id: UUID,
    private val internalName: String,
    @property:GraphQLDescription("If true, the Product is visible to customers.")
    val isPubliclyVisible: Boolean,
    private val defaultVariantId: UUID
) : Node(id) {

    @GraphQLDescription("An internal name to identify the Product, not visible to customers.")
    fun internalName(dfe: DataFetchingEnvironment): String {
        dfe.authorizedUser.checkIsEmployee()
        return internalName
    }

    @GraphQLDescription("The default variant of the product.")
    fun defaultVariant(
        dfe: DataFetchingEnvironment
    ): CompletableFuture<ProductVariant> {
        if (!this.isPubliclyVisible) {
            dfe.authorizedUser.checkIsEmployee()
        }
        return dfe.dataLoader<UUID, ProductVariant>(ProductVariantDataLoader::class.simpleName!!)
            .load(defaultVariantId, dfe)
    }

    @GraphQLDescription("Get all associated ProductVariants")
    suspend fun variants(
        @GraphQLDescription("Number of items to return")
        first: Int? = null,
        @GraphQLDescription("Number of items to skip")
        skip: Int? = null,
        @GraphQLDescription("Ordering")
        orderBy: ProductVariantOrder? = null,
        @GraphQLDescription("Filtering")
        filter: ProductVariantFilter? = null,
        @GraphQLIgnore
        @Autowired
        entityTemplate: R2dbcEntityTemplate,
        dfe: DataFetchingEnvironment
    ): ProductVariantConnection {
        if (!this.isPubliclyVisible) {
            dfe.authorizedUser.checkIsEmployee()
        }
        return ProductVariantConnection(
            first, skip, filter, Condition.eq("t.productId", id), orderBy, entityTemplate, dfe.authorizedUserOrNull
        )
    }

    @GraphQLDescription("Get all associated Categories")
    suspend fun categories(
        @GraphQLDescription("Number of items to return")
        first: Int? = null,
        @GraphQLDescription("Number of items to skip")
        skip: Int? = null,
        @GraphQLDescription("Ordering")
        orderBy: CategoryOrder? = null,
        @GraphQLIgnore
        @Autowired
        entityTemplate: R2dbcEntityTemplate,
        dfe: DataFetchingEnvironment
    ): CategoryConnection {
        return CategoryConnection(
            first,
            skip,
            Condition.eq("productToCategory.productId", id),
            orderBy,
            entityTemplate,
            dfe.authorizedUserOrNull,
            "INNER JOIN ProductToCategoryEntity productToCategory ON productToCategory.categoryId = t.id"
        )
    }

}
