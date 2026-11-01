package org.misarch.payment.graphql.federation

import org.misarch.common.graphql.ReferenceEntityResolver
import org.misarch.payment.graphql.model.User
import org.springframework.stereotype.Component

/**
 * Federated resolver for [User]s (no database access: the fields are resolved from the id).
 */
@Component
class UserResolver : ReferenceEntityResolver<User>(User::class, ::User)
