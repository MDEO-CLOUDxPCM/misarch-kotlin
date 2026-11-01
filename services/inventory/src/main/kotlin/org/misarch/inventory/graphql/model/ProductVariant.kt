package org.misarch.inventory.graphql.model

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.generator.annotations.GraphQLIgnore
import com.expediagroup.graphql.generator.federation.directives.FieldSet
import com.expediagroup.graphql.generator.federation.directives.KeyDirective
import graphql.schema.DataFetchingEnvironment
import org.misarch.common.graphql.GraphQLDefaultValue
import org.misarch.common.graphql.authorizedUser
import org.misarch.common.persistence.Condition
import org.misarch.inventory.graphql.model.connection.ProductItemConnection
import org.misarch.inventory.graphql.model.connection.ProductItemOfProductVariantFilter
import org.misarch.inventory.graphql.model.connection.ProductItemOrder
import org.misarch.inventory.service.ProductItemService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import java.util.UUID

@GraphQLDescription("Foreign type ProductVariant")
@KeyDirective(fields = FieldSet("id"))
class ProductVariant(
    @property:GraphQLDescription("The uuid identifier of the product variant")
    val id: UUID
) {

    @GraphQLDescription("A product item connection for referenced product items in stock")
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
        filter: ProductItemOfProductVariantFilter? = null,
        @GraphQLIgnore
        @Autowired
        entityTemplate: R2dbcEntityTemplate,
        dfe: DataFetchingEnvironment
    ): ProductItemConnection? {
        dfe.authorizedUser.checkIsEmployee()
        return ProductItemConnection(
            first, skip, filter, Condition.eq("productVariantId", id), orderBy, entityTemplate
        )
    }

    @GraphQLDescription("The number of product items in stock")
    suspend fun inventoryCount(
        @GraphQLIgnore
        @Autowired
        productItemService: ProductItemService
    ): Int {
        return productItemService.countInStorage(id)
    }

}
