package org.misarch.order.graphql.model.connection

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.generator.federation.directives.ShareableDirective
import org.misarch.common.graphql.connection.ConnectionQuery
import org.misarch.order.graphql.model.Discount
import org.misarch.order.graphql.model.Order
import org.misarch.order.graphql.model.OrderItem
import org.misarch.order.persistence.model.DiscountRecord
import org.misarch.order.persistence.model.OrderItemRecord
import org.misarch.order.persistence.model.OrderRecord

/*
 * The connections of the original (async-graphql `nodes`/`hasNextPage`/`totalCount`), each field one statement of the
 * [ConnectionQuery] (SELECT page / probe for a further row / COUNT), run only when the field is selected - the
 * connection pattern of the Kotlin services (docs/PORTING.md §6).
 */

@GraphQLDescription("A connection of orders.")
@ShareableDirective
class OrderConnection(private val query: ConnectionQuery<OrderRecord>) {
    @GraphQLDescription("The resulting entities.")
    suspend fun nodes(): List<Order> = query.nodes().map { Order(it) }

    @GraphQLDescription("Whether this connection has a next page.")
    suspend fun hasNextPage(): Boolean = query.hasNextPage()

    @GraphQLDescription("The total amount of items in this connection.")
    suspend fun totalCount(): Int = query.totalCount()
}

@GraphQLDescription("A connection of order items.")
@ShareableDirective
class OrderItemConnection(private val query: ConnectionQuery<OrderItemRecord>) {
    @GraphQLDescription("The resulting entities.")
    suspend fun nodes(): List<OrderItem> = query.nodes().map { OrderItem(it) }

    @GraphQLDescription("Whether this connection has a next page.")
    suspend fun hasNextPage(): Boolean = query.hasNextPage()

    @GraphQLDescription("The total amount of items in this connection.")
    suspend fun totalCount(): Int = query.totalCount()
}

@GraphQLDescription("A connection of discounts.")
@ShareableDirective
class DiscountConnection(private val query: ConnectionQuery<DiscountRecord>) {
    @GraphQLDescription("The resulting entities.")
    suspend fun nodes(): List<Discount> = query.nodes().map { Discount(it.id) }

    @GraphQLDescription("Whether this connection has a next page.")
    suspend fun hasNextPage(): Boolean = query.hasNextPage()

    @GraphQLDescription("The total amount of items in this connection.")
    suspend fun totalCount(): Int = query.totalCount()
}
