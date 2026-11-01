package org.misarch.`return`.graphql.federation

import org.misarch.common.graphql.ReferenceEntityResolver
import org.misarch.`return`.graphql.model.User
import org.springframework.stereotype.Component

/**
 * Federated resolver for [User]s (no database access).
 */
@Component
class UserResolver : ReferenceEntityResolver<User>(User::class, { User(it) })
