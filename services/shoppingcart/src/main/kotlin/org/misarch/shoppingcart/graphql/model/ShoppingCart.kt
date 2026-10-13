package org.misarch.shoppingcart.graphql.model

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import org.misarch.common.rust.RustUuid
import org.misarch.shoppingcart.service.Messages
import java.time.OffsetDateTime

@GraphQLDescription("The shopping cart of a user.")
class ShoppingCart(
    @property:GraphQLDescription("Timestamp when shopping cart was last updated.")
    val lastUpdatedAt: OffsetDateTime,
    private val internalShoppingcartItems: List<ShoppingCartItem>
) {

    /**
     * In-memory pagination of the original (`shoppingcart.rs`): sort by item id (byte order; `orderBy.field` is
     * ignored), `skip`, `first`, `hasNextPage = totalCount > nodes + skip`.
     */
    @GraphQLDescription("Retrieves shoppingcart items in shopping cart.")
    fun shoppingcartItems(
        @GraphQLDescription("Describes that the `first` N shoppingcarts should be retrieved.")
        first: Int? = null,
        @GraphQLDescription("Describes how many shoppingcarts should be skipped at the beginning.")
        skip: Int? = null,
        @GraphQLDescription("Specifies the order in which shoppingcarts are retrieved.")
        orderBy: CommonOrderInput? = null
    ): ShoppingCartItemConnection {
        Messages.requireUnsigned(first, "first")
        Messages.requireUnsigned(skip, "skip")
        val ascending = (orderBy?.direction ?: OrderDirection.ASC) == OrderDirection.ASC
        val order = if (ascending) RustUuid.ORDER else RustUuid.ORDER.reversed()
        val sorted = internalShoppingcartItems.sortedWith(compareBy(order) { it.id })
        val definitelySkip = skip ?: 0
        val nodes = sorted.drop(definitelySkip).take(first ?: Int.MAX_VALUE)
        return ShoppingCartItemConnection(
            nodes = nodes,
            hasNextPage = sorted.size.toLong() > nodes.size.toLong() + definitelySkip,
            totalCount = sorted.size
        )
    }
}
