package org.misarch.user.graphql

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.server.operations.Mutation
import graphql.schema.DataFetchingEnvironment
import org.misarch.common.graphql.authorizedUser
import org.misarch.user.graphql.input.UpdateUserInput
import org.misarch.user.graphql.model.User
import org.misarch.user.service.UserService
import org.springframework.stereotype.Component

/**
 * Defines GraphQL mutations (not transactional, like the original)
 *
 * @property userService service for users
 */
@Component
class Mutation(
    private val userService: UserService
) : Mutation {

    suspend fun updateUser(
        @GraphQLDescription("Input for the updateUser mutation")
        input: UpdateUserInput,
        dfe: DataFetchingEnvironment
    ): User {
        val authorizedUser = dfe.authorizedUser
        if (authorizedUser.id != input.id) {
            authorizedUser.checkIsEmployee()
        }
        return userService.updateUser(input).toDTO()
    }

}
