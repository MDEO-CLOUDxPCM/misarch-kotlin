package org.misarch.order.graphql.model

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.generator.annotations.GraphQLIgnore
import com.expediagroup.graphql.generator.federation.directives.FieldSet
import com.expediagroup.graphql.generator.federation.directives.KeyDirective
import org.misarch.order.graphql.input.CommonOrderInput
import org.misarch.order.graphql.model.connection.OrderItemConnection
import org.misarch.order.graphql.model.connection.paginateInMemory
import org.misarch.order.persistence.model.OrderRecord
import org.misarch.order.rust.U64
import java.time.Instant
import java.util.UUID

@GraphQLDescription("The order of a user.")
@KeyDirective(fields = FieldSet("id"))
class Order(
    @property:GraphQLIgnore
    val record: OrderRecord,
) {
    @GraphQLDescription("Order UUID.")
    val id: UUID = record.id

    @GraphQLDescription("User owning order.")
    val user: User = User(record.userId)

    @GraphQLDescription("Timestamp when order was created.")
    val createdAt: Instant = record.createdAt

    @GraphQLDescription("The status of the order.")
    val orderStatus: OrderStatus = record.orderStatus

    @GraphQLDescription("Timestamp of order placement. `None` until order is placed.")
    val placedAt: Instant? = record.placedAt

    @GraphQLDescription("The rejection reason if status of the order is `OrderStatus::Rejected`.")
    val rejectionReason: RejectionReason? = record.rejectionReason

    @GraphQLDescription("Address of invoice.")
    val invoiceAddress: UserAddress = UserAddress(record.invoiceAddressId)

    @GraphQLDescription("Total compensatable amount of order.")
    fun compensatableOrderAmount(): Int = U64.graphqlInt(record.compensatableOrderAmount)

    @GraphQLDescription("UUID of payment information that the order should be processed with.")
    val paymentInformationId: UUID = record.paymentInformationId

    @GraphQLDescription("Retrieves order items.")
    fun orderItems(
        @GraphQLDescription("Describes that the `first` N order items should be retrieved.")
        first: Int? = null,
        @GraphQLDescription("Describes how many order items should be skipped at the beginning.")
        skip: Int? = null,
        @GraphQLDescription("Specifies the order in which order items are retrieved.")
        orderBy: CommonOrderInput? = null,
    ): OrderItemConnection {
        val page = paginateInMemory(record.items, { it.id }, first, skip, orderBy?.direction)
        return OrderItemConnection(page.nodes.map { OrderItem(it) }, page.hasNextPage, page.totalCount)
    }
}
