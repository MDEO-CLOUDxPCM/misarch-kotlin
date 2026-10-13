package org.misarch.`return`.graphql

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.server.operations.Query
import graphql.schema.DataFetchingEnvironment
import org.misarch.`return`.graphql.model.Return
import org.misarch.`return`.graphql.model.connection.ReturnConnection
import org.misarch.`return`.graphql.model.connection.ReturnOrder
import org.misarch.`return`.persistence.repository.OrderRepository
import org.misarch.`return`.persistence.repository.ReturnRepository
import org.misarch.common.graphql.authorizedUser
import org.misarch.common.graphql.authorizedUserOrNull
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Defines GraphQL queries
 *
 * @property returnRepository The return repository
 * @property orderRepository The order repository
 * @property entityTemplate executes the connection queries
 */
@Component
class Query(
    private val returnRepository: ReturnRepository,
    private val orderRepository: OrderRepository,
    private val entityTemplate: R2dbcEntityTemplate,
) : Query {

    @GraphQLDescription("Get all returns")
    suspend fun returns(
        @GraphQLDescription("Number of items to return")
        first: Int? = null,
        @GraphQLDescription("Number of items to skip")
        skip: Int? = null,
        @GraphQLDescription("Ordering")
        orderBy: ReturnOrder? = null,
        dfe: DataFetchingEnvironment
    ): ReturnConnection {
        return ReturnConnection(first, skip, null, orderBy, entityTemplate, dfe.authorizedUserOrNull)
    }

    @GraphQLDescription("Get a return by id")
    suspend fun `return`(
        @GraphQLDescription("The id of the return")
        id: UUID,
        dfe: DataFetchingEnvironment
    ): Return {
        // like the original: database reads first, authentication afterwards
        val returnEntity = returnRepository.findById(id) ?: throw NoSuchElementException()
        val orderEntity = orderRepository.findById(returnEntity.orderId) ?: throw NoSuchElementException()
        val authorizedUser = dfe.authorizedUser
        if (!authorizedUser.isEmployee) {
            require(orderEntity.userId == authorizedUser.id) {
                "You are not authorized to view this return"
            }
        }
        return returnEntity.toDTO()
    }
}
