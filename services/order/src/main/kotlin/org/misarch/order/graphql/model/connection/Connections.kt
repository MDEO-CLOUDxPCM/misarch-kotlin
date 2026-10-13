package org.misarch.order.graphql.model.connection

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.generator.federation.directives.ShareableDirective
import org.misarch.order.graphql.model.Discount
import org.misarch.order.graphql.model.Order
import org.misarch.order.graphql.model.OrderDirection
import org.misarch.order.graphql.model.OrderItem
import org.misarch.order.graphql.scalar.unsignedArgument
import org.misarch.common.rust.RustUuid
import java.util.UUID

@GraphQLDescription("A connection of orders.")
@ShareableDirective
class OrderConnection(
    @property:GraphQLDescription("The resulting entities.")
    val nodes: List<Order>,
    @property:GraphQLDescription("Whether this connection has a next page.")
    val hasNextPage: Boolean,
    @property:GraphQLDescription("The total amount of items in this connection.")
    val totalCount: Int,
)

@GraphQLDescription("A connection of order items.")
@ShareableDirective
class OrderItemConnection(
    @property:GraphQLDescription("The resulting entities.")
    val nodes: List<OrderItem>,
    @property:GraphQLDescription("Whether this connection has a next page.")
    val hasNextPage: Boolean,
    @property:GraphQLDescription("The total amount of items in this connection.")
    val totalCount: Int,
)

@GraphQLDescription("A connection of discounts.")
@ShareableDirective
class DiscountConnection(
    @property:GraphQLDescription("The resulting entities.")
    val nodes: List<Discount>,
    @property:GraphQLDescription("Whether this connection has a next page.")
    val hasNextPage: Boolean,
    @property:GraphQLDescription("The total amount of items in this connection.")
    val totalCount: Int,
)

/** A page of an in-memory list (`Order.orderItems`, `OrderItem.discounts`). */
data class InMemoryPage<T>(val nodes: List<T>, val hasNextPage: Boolean, val totalCount: Int)

/**
 * In-memory pagination of the original (`order.rs`/`order_item.rs`): sort by id (byte order; the order field is
 * ignored, only `ID` exists), skip `skip` (default 0), take `first` (default all),
 * `hasNextPage = totalCount > nodes + skip`. `first`/`skip` are Rust `usize`.
 */
fun <T> paginateInMemory(
    items: List<T>,
    id: (T) -> UUID,
    first: Int?,
    skip: Int?,
    direction: OrderDirection?,
): InMemoryPage<T> {
    val definitelyFirst = unsignedArgument(first)
    val definitelySkip = unsignedArgument(skip) ?: 0
    val comparator = Comparator<T> { a, b -> RustUuid.ORDER.compare(id(a), id(b)) }
    val sorted = items.sortedWith(if (direction == OrderDirection.DESC) comparator.reversed() else comparator)
    val part = sorted.drop(definitelySkip).let { if (definitelyFirst != null) it.take(definitelyFirst) else it }
    return InMemoryPage(part, sorted.size.toLong() > part.size.toLong() + definitelySkip, sorted.size)
}
