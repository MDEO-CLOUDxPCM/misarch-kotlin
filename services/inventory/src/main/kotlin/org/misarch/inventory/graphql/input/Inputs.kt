package org.misarch.inventory.graphql.input

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.generator.annotations.GraphQLName
import com.expediagroup.graphql.generator.annotations.GraphQLSkipInputSuffix
import org.misarch.inventory.graphql.model.OrderDirection
import org.misarch.inventory.graphql.model.ProductItemOrderField
import org.misarch.inventory.graphql.model.ProductItemStatus
import org.misarch.common.nest.Uuid

@GraphQLDescription("Ordering options for product items")
@GraphQLName("ProductItemOrder")
@GraphQLSkipInputSuffix
data class ProductItemOrder(
    @property:GraphQLDescription("The field to order by")
    val field: ProductItemOrderField? = null,
    @property:GraphQLDescription("The direction to order by")
    val direction: OrderDirection? = null,
)

@GraphQLDescription("Filtering options for product items")
@GraphQLName("ProductItemFilter")
@GraphQLSkipInputSuffix
data class ProductItemFilter(
    @property:GraphQLDescription("Linked product variant id")
    val productVariant: Uuid? = null,
    @property:GraphQLDescription("Current product item status")
    val inventoryStatus: ProductItemStatus? = null,
)

@GraphQLDescription("Filtering options for product items of product variants")
@GraphQLName("ProductItemOfProductVariantFilter")
@GraphQLSkipInputSuffix
data class ProductItemOfProductVariantFilter(
    @property:GraphQLDescription("Current product item status")
    val inventoryStatus: ProductItemStatus? = null,
)

@GraphQLDescription("The input of a product item batch creation")
data class CreateProductItemBatchInput(
    @property:GraphQLDescription("The product variant id of the product item")
    val productVariantId: Uuid,
    @property:GraphQLDescription("The number of products to add")
    val number: Int,
)

@GraphQLDescription("The input of a product item update")
data class UpdateProductItemInput(
    @property:GraphQLDescription("The product item identifier")
    val id: Uuid,
    @property:GraphQLDescription("The product variant id of the product item")
    val productVariantId: Uuid,
    @property:GraphQLDescription("The inventory state of the product item")
    val inventoryStatus: ProductItemStatus,
)

@GraphQLDescription("The input to reserve a batch of product items")
data class ReserveProductItemsBatchInput(
    @property:GraphQLDescription("The product variant id of the product item")
    val productVariantId: Uuid,
    @property:GraphQLDescription("The number of product items to reserve")
    val number: Int,
    @property:GraphQLDescription("The order id that reserves the product items")
    val orderId: Uuid,
)
