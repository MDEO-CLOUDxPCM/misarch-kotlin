package org.misarch.address.graphql.dataloader

import org.misarch.address.graphql.model.User
import org.misarch.address.persistence.model.UserEntity
import org.misarch.address.persistence.repository.UserRepository
import org.misarch.common.graphql.dataloader.IdDataLoader
import org.springframework.stereotype.Component

/**
 * Data loader for [User]s. FIXED (docs/fixes/address.md): an unknown id fails only its own key
 * (the original failed the whole batch with a NullPointerException).
 *
 * @param repository repository for [UserEntity]s
 */
@Component
class UserDataLoader(
    repository: UserRepository
) : IdDataLoader<User, UserEntity>(repository) {
    override val failMissingKeysIndividually: Boolean = true
}
