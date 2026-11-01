package org.misarch.inventory.graphql.model

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.generator.federation.directives.FieldSet
import com.expediagroup.graphql.generator.federation.directives.KeyDirective
import java.util.UUID

@GraphQLDescription("A product Item of a product variant")
@KeyDirective(fields = FieldSet("id"))
class ProductItem(
    @property:GraphQLDescription("The uuid identifier of the product item")
    val id: UUID,
    private val productVariantId: UUID,
    @property:GraphQLDescription("Describes the inventory status of the product item")
    val inventoryStatus: ProductItemStatus,
    @property:GraphQLDescription("Reference to the order that reserved the product item")
    val orderId: UUID?
) {

    @GraphQLDescription("The corresponding product variant")
    fun productVariant(): ProductVariant {
        return ProductVariant(productVariantId)
    }

}
