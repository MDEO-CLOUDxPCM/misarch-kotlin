package org.misarch.notification.graphql.federation

import org.misarch.common.graphql.ReferenceEntityResolver
import org.misarch.notification.graphql.model.User
import org.springframework.stereotype.Component

/**
 * Federated resolver for [User]s (no database access: a user reference only carries the id).
 */
@Component
class UserResolver : ReferenceEntityResolver<User>(User::class, { User(it) })
