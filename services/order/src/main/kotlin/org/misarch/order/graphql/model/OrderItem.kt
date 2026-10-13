package org.misarch.order.graphql.model

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.generator.annotations.GraphQLIgnore
import com.expediagroup.graphql.generator.federation.directives.FieldSet
import com.expediagroup.graphql.generator.federation.directives.KeyDirective
import org.misarch.order.graphql.input.CommonOrderInput
import org.misarch.order.graphql.model.connection.DiscountConnection
import org.misarch.order.graphql.model.connection.paginateInMemory
import org.misarch.order.persistence.model.OrderItemRecord
import org.misarch.order.rust.U64
import java.time.Instant
import java.util.UUID

@GraphQLDescription("Describes an order item of an order.")
@KeyDirective(fields = FieldSet("id"))
class OrderItem(
    @property:GraphQLIgnore
    val record: OrderItemRecord,
) {
    @GraphQLDescription("order item UUID.")
    val id: UUID = record.id

    @GraphQLDescription("Timestamp when order item was created.")
    val createdAt: Instant = record.createdAt

    @GraphQLDescription("Product variant associated with order item.")
    val productVariant: ProductVariant = ProductVariant(record.productVariantId)

    @GraphQLDescription("Product variant version associated with order item.")
    val productVariantVersion: ProductVariantVersion = ProductVariantVersion(record.productVariantVersion.id)

    @GraphQLDescription("Tax rate version associated with order item.")
    val taxRateVersion: TaxRateVersion = TaxRateVersion(record.taxRateVersion.id)

    @GraphQLDescription("Shopping cart item associated with order item.")
    val shoppingCartItem: ShoppingCartItem = ShoppingCartItem(record.shoppingCartItemId)

    @GraphQLDescription("Specifies the quantity of the order item.")
    fun count(): Int = U64.graphqlInt(record.count)

    @GraphQLDescription("Total cost of product item, which can also be refunded.")
    fun compensatableAmount(): Int = U64.graphqlInt(record.compensatableAmount)

    @GraphQLDescription("Shipment method of order item.")
    val shipmentMethod: ShipmentMethod = ShipmentMethod(record.shipmentMethodId)

    @GraphQLDescription("Retrieves discounts.")
    fun discounts(
        @GraphQLDescription("Describes that the `first` N discounts should be retrieved.")
        first: Int? = null,
        @GraphQLDescription("Describes how many discounts should be skipped at the beginning.")
        skip: Int? = null,
        @GraphQLDescription("Specifies the order in which discounts are retrieved.")
        orderBy: CommonOrderInput? = null,
    ): DiscountConnection {
        val page = paginateInMemory(record.discounts, { it.id }, first, skip, orderBy?.direction)
        return DiscountConnection(page.nodes.map { Discount(it.id) }, page.hasNextPage, page.totalCount)
    }
}
