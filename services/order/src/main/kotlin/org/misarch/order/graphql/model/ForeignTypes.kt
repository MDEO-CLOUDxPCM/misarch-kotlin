package org.misarch.order.graphql.model

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.generator.federation.directives.FieldSet
import com.expediagroup.graphql.generator.federation.directives.KeyDirective
import java.util.UUID

/* Foreign types (`@key(fields: "id", resolvable: false)`) referenced by orders and order items (foreign_types.rs). */

@GraphQLDescription("Foreign type of a discount.")
@KeyDirective(fields = FieldSet("id"), resolvable = false)
class Discount(
    @property:GraphQLDescription("UUID of the discount.")
    val id: UUID,
)

@GraphQLDescription("Foreign type of a product variant.")
@KeyDirective(fields = FieldSet("id"), resolvable = false)
class ProductVariant(
    @property:GraphQLDescription("UUID of the product variant.")
    val id: UUID,
)

@GraphQLDescription("Foreign type of a product variant.")
@KeyDirective(fields = FieldSet("id"), resolvable = false)
class ProductVariantVersion(
    @property:GraphQLDescription("UUID of the product variant version.")
    val id: UUID,
)

@GraphQLDescription("Describes the method/provider that the shipment uses.")
@KeyDirective(fields = FieldSet("id"), resolvable = false)
class ShipmentMethod(
    @property:GraphQLDescription("UUID of the shipment method.")
    val id: UUID,
)

@GraphQLDescription("Foreign type of a shopping cart item.")
@KeyDirective(fields = FieldSet("id"), resolvable = false)
class ShoppingCartItem(
    @property:GraphQLDescription("UUID of the shopping cart item.")
    val id: UUID,
)

@GraphQLDescription("Foreign type of a tax rate version.")
@KeyDirective(fields = FieldSet("id"), resolvable = false)
class TaxRateVersion(
    @property:GraphQLDescription("UUID of the tax rate.")
    val id: UUID,
)

@GraphQLDescription("Foreign type of an user address.")
@KeyDirective(fields = FieldSet("id"), resolvable = false)
class UserAddress(
    @property:GraphQLDescription("UUID of the product item.")
    val id: UUID,
)
