package org.misarch.shoppingcart.graphql.model

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.generator.annotations.GraphQLIgnore
import org.misarch.shoppingcart.persistence.ShoppingcartRepository
import org.misarch.shoppingcart.service.Messages
import org.springframework.beans.factory.annotation.Autowired
import java.time.OffsetDateTime
import java.util.UUID

/**
 * The shopping cart of a user (flattened into the user's `UserEntity` row); [userId] identifies its items.
 */
@GraphQLDescription("The shopping cart of a user.")
class ShoppingCart(
    private val userId: UUID,
    @property:GraphQLDescription("Timestamp when shopping cart was last updated.")
    val lastUpdatedAt: OffsetDateTime,
) {

    /**
     * Pagination of the original (`shoppingcart.rs`, in memory there): sort by item id (byte order; `orderBy.field` is
     * ignored), `skip`, `first`, `hasNextPage = totalCount > nodes + skip` (= a further item exists after
     * `first + skip`). Here a connection over the user's `ShoppingCartItemEntity` rows, read only when selected.
     */
    @GraphQLDescription("Retrieves shoppingcart items in shopping cart.")
    fun shoppingcartItems(
        @GraphQLDescription("Describes that the `first` N shoppingcarts should be retrieved.")
        first: Int? = null,
        @GraphQLDescription("Describes how many shoppingcarts should be skipped at the beginning.")
        skip: Int? = null,
        @GraphQLDescription("Specifies the order in which shoppingcarts are retrieved.")
        orderBy: CommonOrderInput? = null,
        @GraphQLIgnore
        @Autowired
        repository: ShoppingcartRepository,
    ): ShoppingCartItemConnection {
        Messages.requireUnsigned(first, "first")
        Messages.requireUnsigned(skip, "skip")
        val ascending = (orderBy?.direction ?: OrderDirection.ASC) == OrderDirection.ASC
        return ShoppingCartItemConnection(repository.itemsOfUser(userId, first, skip ?: 0, ascending))
    }
}
