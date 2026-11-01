package org.misarch.inventory.graphql.model.connection

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.generator.annotations.GraphQLSkipInputSuffix
import org.misarch.common.graphql.connection.BaseFilter
import org.misarch.common.persistence.Condition
import org.misarch.inventory.graphql.model.ProductItemStatus
import java.util.UUID

@GraphQLDescription("Filtering options for product items")
@GraphQLSkipInputSuffix
class ProductItemFilter(
    @property:GraphQLDescription("Linked product variant id")
    val productVariant: UUID?,
    @property:GraphQLDescription("Current product item status")
    val inventoryStatus: ProductItemStatus?
) : BaseFilter {

    override fun toCondition(): Condition? {
        return Condition.allOf(
            productVariant?.let { Condition.eq("productVariantId", it) },
            inventoryStatus?.let { Condition.eq("inventoryStatus", it.name) }
        )
    }

}

@GraphQLDescription("Filtering options for product items of product variants")
@GraphQLSkipInputSuffix
class ProductItemOfProductVariantFilter(
    @property:GraphQLDescription("Current product item status")
    val inventoryStatus: ProductItemStatus?
) : BaseFilter {

    override fun toCondition(): Condition? {
        return inventoryStatus?.let { Condition.eq("inventoryStatus", it.name) }
    }

}
