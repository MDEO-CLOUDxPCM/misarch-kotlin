package org.misarch.user.graphql

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.server.operations.Query
import graphql.schema.DataFetchingEnvironment
import org.misarch.common.graphql.authorizedUser
import org.misarch.common.graphql.authorizedUserOrNull
import org.misarch.common.graphql.load
import org.misarch.common.persistence.findByIdOrThrow
import org.misarch.user.graphql.dataloader.UserDataLoader
import org.misarch.user.graphql.model.User
import org.misarch.user.graphql.model.connection.UserConnection
import org.misarch.user.graphql.model.connection.UserOrder
import org.misarch.user.persistence.repository.UserRepository
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import org.springframework.stereotype.Component
import java.util.UUID
import java.util.concurrent.CompletableFuture

/**
 * Defines GraphQL queries
 *
 * @property userRepository repository for users
 * @property entityTemplate executes the connection queries
 */
@Component
class Query(
    private val userRepository: UserRepository,
    private val entityTemplate: R2dbcEntityTemplate,
) : Query {

    @GraphQLDescription("Get a user by id")
    fun user(
        @GraphQLDescription("The id of the user")
        id: UUID,
        dfe: DataFetchingEnvironment
    ): CompletableFuture<User> {
        val authorizedUser = dfe.authorizedUser
        if (authorizedUser.id != id) {
            authorizedUser.checkIsEmployee()
        }
        return dfe.load(UserDataLoader::class, id)
    }

    @GraphQLDescription("Get all users")
    suspend fun users(
        @GraphQLDescription("Number of items to return")
        first: Int? = null,
        @GraphQLDescription("Number of items to skip")
        skip: Int? = null,
        @GraphQLDescription("Ordering")
        orderBy: UserOrder? = null,
        dfe: DataFetchingEnvironment
    ): UserConnection {
        dfe.authorizedUser.checkIsEmployee()
        return UserConnection(first, skip, null, orderBy, entityTemplate, dfe.authorizedUserOrNull)
    }

    @GraphQLDescription("Get the currently authenticated user")
    suspend fun currentUser(
        dfe: DataFetchingEnvironment
    ): User? {
        return dfe.authorizedUserOrNull?.id?.let {
            // original: findById(it).awaitSingle() -> NoSuchElementException if the user has no row
            (userRepository.findByIdOrThrow(it))
                .toDTO()
        }
    }

}
