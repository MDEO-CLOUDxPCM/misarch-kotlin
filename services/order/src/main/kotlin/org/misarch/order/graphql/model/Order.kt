package org.misarch.order.graphql.model

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.generator.annotations.GraphQLIgnore
import com.expediagroup.graphql.generator.federation.directives.FieldSet
import com.expediagroup.graphql.generator.federation.directives.KeyDirective
import org.misarch.common.persistence.Condition
import org.misarch.order.graphql.model.connection.CommonOrderInput
import org.misarch.order.graphql.model.connection.OrderItemConnection
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import java.time.OffsetDateTime
import java.util.UUID

@GraphQLDescription("The order of a user.")
@KeyDirective(fields = FieldSet("id"))
class Order(
    @property:GraphQLDescription("Order UUID.")
    val id: UUID,
    internal val userId: UUID,
    @property:GraphQLDescription("Timestamp when order was created.")
    val createdAt: OffsetDateTime,
    @property:GraphQLDescription("The status of the order.")
    val orderStatus: OrderStatus,
    @property:GraphQLDescription("Timestamp of order placement. `None` until order is placed.")
    val placedAt: OffsetDateTime?,
    @property:GraphQLDescription("The rejection reason if status of the order is `OrderStatus::Rejected`.")
    val rejectionReason: RejectionReason?,
    private val invoiceAddressId: UUID,
    @property:GraphQLDescription("Total compensatable amount of order.")
    val compensatableOrderAmount: Int,
    @property:GraphQLDescription("UUID of payment information that the order should be processed with.")
    val paymentInformationId: UUID
) {

    @GraphQLDescription("User owning order.")
    fun user(): User = User(userId)

    @GraphQLDescription("Address of invoice.")
    fun invoiceAddress(): UserAddress = UserAddress(invoiceAddressId)

    @GraphQLDescription("Retrieves order items.")
    fun orderItems(
        @GraphQLDescription("Describes that the `first` N order items should be retrieved.")
        first: Int? = null,
        @GraphQLDescription("Describes how many order items should be skipped at the beginning.")
        skip: Int? = null,
        @GraphQLDescription("Specifies the order in which order items are retrieved.")
        orderBy: CommonOrderInput? = null,
        @GraphQLIgnore
        @Autowired
        entityTemplate: R2dbcEntityTemplate
    ): OrderItemConnection {
        return OrderItemConnection(first, skip, Condition.eq("orderId", id), orderBy, entityTemplate)
    }

}
