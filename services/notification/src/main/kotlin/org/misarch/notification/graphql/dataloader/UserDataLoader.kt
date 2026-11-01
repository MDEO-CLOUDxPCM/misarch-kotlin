package org.misarch.notification.graphql.dataloader

import org.misarch.common.graphql.dataloader.IdDataLoader
import org.misarch.notification.graphql.model.User
import org.misarch.notification.persistence.model.UserEntity
import org.misarch.notification.persistence.repository.UserRepository
import org.springframework.stereotype.Component

/**
 * Data loader for [User]s. An unknown id fails only its own key, not the whole batch.
 */
@Component
class UserDataLoader(
    repository: UserRepository
) : IdDataLoader<User, UserEntity>(repository) {
}
