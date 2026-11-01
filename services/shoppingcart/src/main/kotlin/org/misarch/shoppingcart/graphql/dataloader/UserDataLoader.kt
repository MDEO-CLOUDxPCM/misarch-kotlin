package org.misarch.shoppingcart.graphql.dataloader

import org.misarch.common.graphql.dataloader.IdDataLoader
import org.misarch.shoppingcart.graphql.model.User
import org.misarch.shoppingcart.persistence.model.UserEntity
import org.misarch.shoppingcart.persistence.repository.UserRepository
import org.springframework.stereotype.Component

/**
 * Data loader for [User]s (with their shopping cart)
 */
@Component
class UserDataLoader(
    repository: UserRepository
) : IdDataLoader<User, UserEntity>(repository)
