package org.misarch.shoppingcart.persistence.model

import org.misarch.common.persistence.BaseEntity
import org.misarch.shoppingcart.graphql.model.ShoppingCart
import org.misarch.shoppingcart.graphql.model.User
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Entity for users (replica of the user service's users, fed by `user/user/created`) with their shopping cart:
 * a user has exactly one cart, its items are [ShoppingCartItemEntity]s
 *
 * @property shoppingcartLastUpdatedAt when the user's shopping cart was last changed
 * @property id unique identifier of the user
 */
@Table
class UserEntity(
    val shoppingcartLastUpdatedAt: OffsetDateTime,
    @Id
    override val id: UUID
) : BaseEntity<User> {

    override fun toDTO(): User {
        return User(
            id = id,
            shoppingcart = ShoppingCart(
                userId = id,
                lastUpdatedAt = shoppingcartLastUpdatedAt
            )
        )
    }

}
