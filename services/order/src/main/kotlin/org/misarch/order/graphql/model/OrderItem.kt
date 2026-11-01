package org.misarch.order.graphql.model

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.generator.annotations.GraphQLIgnore
import com.expediagroup.graphql.generator.federation.directives.FieldSet
import com.expediagroup.graphql.generator.federation.directives.KeyDirective
import org.misarch.common.persistence.Condition
import org.misarch.order.graphql.model.connection.CommonOrder
import org.misarch.order.graphql.model.connection.DiscountConnection
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import java.time.OffsetDateTime
import java.util.UUID

@GraphQLDescription("Describes an order item of an order.")
@KeyDirective(fields = FieldSet("id"))
class OrderItem(
    @property:GraphQLDescription("order item UUID.")
    val id: UUID,
    internal val orderId: UUID,
    @property:GraphQLDescription("Timestamp when order item was created.")
    val createdAt: OffsetDateTime,
    private val productVariantId: UUID,
    private val productVariantVersionId: UUID,
    private val taxRateVersionId: UUID,
    private val shoppingCartItemId: UUID,
    @property:GraphQLDescription("Specifies the quantity of the order item.")
    val count: Int,
    @property:GraphQLDescription("Total cost of product item, which can also be refunded.")
    val compensatableAmount: Int,
    private val shipmentMethodId: UUID
) {

    @GraphQLDescription("Product variant associated with order item.")
    fun productVariant(): ProductVariant = ProductVariant(productVariantId)

    @GraphQLDescription("Product variant version associated with order item.")
    fun productVariantVersion(): ProductVariantVersion = ProductVariantVersion(productVariantVersionId)

    @GraphQLDescription("Tax rate version associated with order item.")
    fun taxRateVersion(): TaxRateVersion = TaxRateVersion(taxRateVersionId)

    @GraphQLDescription("Shopping cart item associated with order item.")
    fun shoppingCartItem(): ShoppingCartItem = ShoppingCartItem(shoppingCartItemId)

    @GraphQLDescription("Shipment method of order item.")
    fun shipmentMethod(): ShipmentMethod = ShipmentMethod(shipmentMethodId)

    @GraphQLDescription("Retrieves discounts.")
    fun discounts(
        @GraphQLDescription("Describes that the `first` N discounts should be retrieved.")
        first: Int? = null,
        @GraphQLDescription("Describes how many discounts should be skipped at the beginning.")
        skip: Int? = null,
        @GraphQLDescription("Specifies the order in which discounts are retrieved.")
        orderBy: CommonOrder? = null,
        @GraphQLIgnore
        @Autowired
        entityTemplate: R2dbcEntityTemplate
    ): DiscountConnection {
        return DiscountConnection(first, skip, Condition.eq("orderItemId", id), orderBy, entityTemplate)
    }

}
