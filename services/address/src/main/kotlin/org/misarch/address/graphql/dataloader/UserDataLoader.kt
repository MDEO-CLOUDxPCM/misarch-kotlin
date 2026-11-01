package org.misarch.address.graphql.dataloader

import org.misarch.address.graphql.model.User
import org.misarch.address.persistence.model.UserEntity
import org.misarch.address.persistence.repository.UserRepository
import org.misarch.common.graphql.dataloader.IdDataLoader
import org.springframework.stereotype.Component

/**
 * Data loader for [User]s. An unknown id fails only its own key, not the whole batch.
 */
@Component
class UserDataLoader(
    repository: UserRepository
) : IdDataLoader<User, UserEntity>(repository) {
}
