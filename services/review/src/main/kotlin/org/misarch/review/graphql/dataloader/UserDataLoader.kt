package org.misarch.review.graphql.dataloader

import org.misarch.common.graphql.dataloader.IdDataLoader
import org.misarch.review.graphql.model.User
import org.misarch.review.persistence.model.UserEntity
import org.misarch.review.persistence.repository.UserRepository
import org.springframework.stereotype.Component

/**
 * Data loader for [User]s (replicated ids only)
 */
@Component
class UserDataLoader(
    repository: UserRepository
) : IdDataLoader<User, UserEntity>(repository)
