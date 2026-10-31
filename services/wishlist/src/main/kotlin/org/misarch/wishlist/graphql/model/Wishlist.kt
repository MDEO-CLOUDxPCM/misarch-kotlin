package org.misarch.wishlist.graphql.model

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.generator.annotations.GraphQLIgnore
import com.expediagroup.graphql.generator.federation.directives.FieldSet
import com.expediagroup.graphql.generator.federation.directives.KeyDirective
import org.misarch.wishlist.persistence.WishlistRepository
import org.misarch.wishlist.persistence.WishlistRow
import org.misarch.wishlist.service.Messages
import org.springframework.beans.factory.annotation.Autowired
import java.time.OffsetDateTime
import java.util.UUID

@GraphQLDescription("The wishlist of a user.")
@KeyDirective(fields = FieldSet("id"))
class Wishlist(
    @property:GraphQLDescription("Wishlist UUID.")
    val id: UUID,
    @property:GraphQLDescription("User.")
    val user: User,
    @property:GraphQLDescription("Name of wishlist.")
    val name: String,
    @property:GraphQLDescription("Timestamp when wishlist was created.")
    val createdAt: OffsetDateTime,
    @property:GraphQLDescription("Timestamp when wishlist was last updated.")
    val lastUpdatedAt: OffsetDateTime,
) {

    /**
     * Pagination of the original (`wishlist.rs`, in memory there): sort by id (byte order; `orderBy.field` is ignored),
     * `skip`, `first`, `hasNextPage = totalCount > nodes + skip` (= a further variant exists after `first + skip`).
     * Here a connection over the wishlist's `WishlistToProductVariantEntity` rows, read only when selected.
     */
    @GraphQLDescription("Retrieves product variants.")
    fun productVariants(
        @GraphQLDescription("Describes that the `first` N product variants should be retrieved.")
        first: Int? = null,
        @GraphQLDescription("Describes how many product variants should be skipped at the beginning.")
        skip: Int? = null,
        @GraphQLDescription("Specifies the order in which product variants are retrieved.")
        orderBy: CommonOrderInput? = null,
        @GraphQLIgnore
        @Autowired
        repository: WishlistRepository,
    ): ProductVariantConnection {
        Messages.requireUnsigned(first, "first")
        Messages.requireUnsigned(skip, "skip")
        val ascending = (orderBy?.direction ?: OrderDirection.ASC) == OrderDirection.ASC
        return ProductVariantConnection(repository.productVariantsOfWishlist(id, first, skip ?: 0, ascending))
    }

    companion object {
        fun of(row: WishlistRow) = Wishlist(row.id, User(row.userId), row.name, row.createdAt, row.lastUpdatedAt)
    }
}
