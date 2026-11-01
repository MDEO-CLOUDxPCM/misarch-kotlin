package org.misarch.wishlist.persistence.model

import org.misarch.common.persistence.BaseEntity
import org.misarch.common.persistence.requiredId
import org.misarch.wishlist.graphql.model.User
import org.misarch.wishlist.graphql.model.Wishlist
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Entity for wishlists; their product variants are [WishlistToProductVariantEntity]s
 *
 * @property userId id of the user owning the wishlist
 * @property name name of the wishlist
 * @property createdAt when the wishlist was created
 * @property lastUpdatedAt when the wishlist was last changed
 * @property id unique identifier of the wishlist
 */
@Table
class WishlistEntity(
    val userId: UUID,
    var name: String,
    val createdAt: OffsetDateTime,
    var lastUpdatedAt: OffsetDateTime,
    @Id
    override val id: UUID?
) : BaseEntity<Wishlist> {

    override fun toDTO(): Wishlist {
        return Wishlist(
            id = requiredId,
            user = User(userId),
            name = name,
            createdAt = createdAt,
            lastUpdatedAt = lastUpdatedAt
        )
    }

}
