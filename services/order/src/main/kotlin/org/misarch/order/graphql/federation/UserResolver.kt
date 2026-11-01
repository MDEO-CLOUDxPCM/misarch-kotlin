package org.misarch.order.graphql.federation

import org.misarch.common.graphql.ReferenceEntityResolver
import org.misarch.order.graphql.model.User
import org.springframework.stereotype.Component

/**
 * Federated resolver for [User]s.
 */
@Component
class UserResolver : ReferenceEntityResolver<User>(User::class, ::User)
