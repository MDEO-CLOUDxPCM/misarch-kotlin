package org.misarch.address.graphql.federation

import org.misarch.address.graphql.model.User
import org.misarch.common.graphql.ReferenceEntityResolver
import org.springframework.stereotype.Component

/**
 * Federated resolver for [User]s (no database access: a user reference only carries the id).
 */
@Component
class UserResolver : ReferenceEntityResolver<User>(User::class, { User(it) })
