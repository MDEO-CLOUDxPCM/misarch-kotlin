package org.misarch.order.graphql.model

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.generator.annotations.GraphQLIgnore
import com.expediagroup.graphql.generator.federation.directives.FieldSet
import com.expediagroup.graphql.generator.federation.directives.KeyDirective
import org.misarch.order.graphql.input.CommonOrderInput
import org.misarch.order.graphql.model.connection.DiscountConnection
import org.misarch.order.graphql.scalar.unsignedArgument
import org.misarch.order.persistence.OrderStore
import org.misarch.order.persistence.model.OrderItemRecord
import org.springframework.beans.factory.annotation.Autowired
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

    /**
     * The item's discounts (`order_item.rs`: sorted by id, byte order; `orderBy.field` is ignored): a connection over
     * `order_item_discounts` of this item, read only when selected; `first` default all, `skip` default 0.
     */
    @GraphQLDescription("Retrieves discounts.")
    suspend fun discounts(
        @GraphQLDescription("Describes that the `first` N discounts should be retrieved.")
        first: Int? = null,
        @GraphQLDescription("Describes how many discounts should be skipped at the beginning.")
        skip: Int? = null,
        @GraphQLDescription("Specifies the order in which discounts are retrieved.")
        orderBy: CommonOrderInput? = null,
        @GraphQLIgnore
        @Autowired
        store: OrderStore,
    ): DiscountConnection {
        val definitelyFirst = unsignedArgument(first)
        val definitelySkip = unsignedArgument(skip) ?: 0
        return DiscountConnection(
            store.discountsOfOrderItem(record.id, definitelyFirst, definitelySkip, orderBy?.direction != OrderDirection.DESC)
        )
    }
}
