package org.misarch.discount.graphql.federation

import org.misarch.common.graphql.ReferenceEntityResolver
import org.misarch.discount.graphql.model.User
import org.springframework.stereotype.Component

/**
 * Federated resolver for [User]s (no database access: the reference only carries the id).
 */
@Component
class UserResolver : ReferenceEntityResolver<User>(User::class, { User(it) })
