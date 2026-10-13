package org.misarch.notification.graphql.dataloader

import org.misarch.common.graphql.dataloader.IdDataLoader
import org.misarch.notification.graphql.model.User
import org.misarch.notification.persistence.model.UserEntity
import org.misarch.notification.persistence.repository.UserRepository
import org.springframework.stereotype.Component

/**
 * Data loader for [User]s. FIXED (docs/fixes/notification.md): an unknown id fails only its own key (the original
 * failed the whole batch with a NullPointerException).
 *
 * @param repository repository for [UserEntity]s
 */
@Component
class UserDataLoader(
    repository: UserRepository
) : IdDataLoader<User, UserEntity>(repository) {
    override val failMissingKeysIndividually: Boolean = true
}
