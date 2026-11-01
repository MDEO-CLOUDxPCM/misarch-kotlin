package org.misarch.wishlist.graphql.dataloader

import org.misarch.common.graphql.dataloader.IdDataLoader
import org.misarch.wishlist.graphql.model.User
import org.misarch.wishlist.persistence.model.UserEntity
import org.misarch.wishlist.persistence.repository.UserRepository
import org.springframework.stereotype.Component

/**
 * Data loader for [User]s
 *
 * @param repository repository for [UserEntity]s
 */
@Component
class UserDataLoader(
    repository: UserRepository
) : IdDataLoader<User, UserEntity>(repository)
