package org.misarch.user.graphql.dataloader

import org.misarch.common.graphql.dataloader.IdDataLoader
import org.misarch.user.graphql.model.User
import org.misarch.user.persistence.model.UserEntity
import org.misarch.user.persistence.repository.UserRepository
import org.springframework.stereotype.Component

/**
 * Data loader for [User]s. FIXED (docs/fixes/user.md): an unknown id fails only its own key (the original failed the
 * whole batch with a NullPointerException).
 *
 * @param repository repository for [UserEntity]s
 */
@Component
class UserDataLoader(
    repository: UserRepository
) : IdDataLoader<User, UserEntity>(repository) {
}
