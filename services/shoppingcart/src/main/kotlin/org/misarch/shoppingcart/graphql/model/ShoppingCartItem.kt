package org.misarch.shoppingcart.graphql.model

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.generator.federation.directives.FieldSet
import com.expediagroup.graphql.generator.federation.directives.KeyDirective
import org.misarch.shoppingcart.persistence.ShoppingCartItemRow
import java.time.OffsetDateTime
import java.util.UUID

@GraphQLDescription("Shopping cart item in a shopping cart of a user.")
@KeyDirective(fields = FieldSet("id"))
class ShoppingCartItem(
    @property:GraphQLDescription("Shopping cart item UUID.")
    val id: UUID,
    @property:GraphQLDescription("Count of items in basket.")
    val count: Int,
    @property:GraphQLDescription("Timestamp when shopping cart item was added.")
    val addedAt: OffsetDateTime,
    @property:GraphQLDescription("Product variant of shopping cart item.")
    val productVariant: ProductVariant
) {
    companion object {
        fun of(row: ShoppingCartItemRow) =
            ShoppingCartItem(row.id, row.count.toInt(), row.addedAt, ProductVariant(row.productVariantId))
    }
}
