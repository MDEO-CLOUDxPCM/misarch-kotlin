package org.misarch.user.graphql.dataloader

import org.misarch.common.graphql.dataloader.IdDataLoader
import org.misarch.user.graphql.model.User
import org.misarch.user.persistence.model.UserEntity
import org.misarch.user.persistence.repository.UserRepository
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
