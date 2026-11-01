package org.misarch.discount.graphql.dataloader

import org.misarch.common.graphql.dataloader.IdDataLoader
import org.misarch.discount.graphql.model.User
import org.misarch.discount.persistence.model.UserEntity
import org.misarch.discount.persistence.repository.UserRepository
import org.springframework.stereotype.Component

/**
 * Data loader for [User]s. An unknown id fails only its own key, not the whole batch.
 */
@Component
class UserDataLoader(
    repository: UserRepository
) : IdDataLoader<User, UserEntity>(repository) {
}
