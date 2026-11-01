package org.misarch.`return`.graphql.model

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.generator.annotations.GraphQLIgnore
import com.expediagroup.graphql.generator.federation.directives.FieldSet
import com.expediagroup.graphql.generator.federation.directives.KeyDirective
import graphql.schema.DataFetchingEnvironment
import org.misarch.common.graphql.authorizedUserOrNull
import org.misarch.common.persistence.Condition
import org.misarch.`return`.graphql.model.connection.CommonOrder
import org.misarch.`return`.graphql.model.connection.OrderItemConnection
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import java.time.OffsetDateTime
import java.util.UUID

@GraphQLDescription("A return.")
@KeyDirective(fields = FieldSet("id"))
class Return(
    id: UUID,
    @property:GraphQLDescription("The reason for the return.")
    val reason: String,
    @property:GraphQLDescription("The amount of money refunded.")
    val refundedAmount: Int,
    @property:GraphQLDescription("The time the return was created.")
    val createdAt: OffsetDateTime,
    private val orderId: UUID
) : Node(id) {

    @GraphQLDescription("The order this return is for.")
    fun order(): Order {
        return Order(orderId)
    }

    @GraphQLDescription("Get all order items returned with this return")
    fun returnedItems(
        @GraphQLDescription("Number of items to return")
        first: Int? = null,
        @GraphQLDescription("Number of items to skip")
        skip: Int? = null,
        @GraphQLDescription("Ordering")
        orderBy: CommonOrder? = null,
        @GraphQLIgnore
        @Autowired
        entityTemplate: R2dbcEntityTemplate,
        dfe: DataFetchingEnvironment
    ): OrderItemConnection {
        return OrderItemConnection(
            first,
            skip,
            Condition.eq("returnedWithId", id),
            orderBy,
            entityTemplate,
            dfe.authorizedUserOrNull
        )
    }

}
