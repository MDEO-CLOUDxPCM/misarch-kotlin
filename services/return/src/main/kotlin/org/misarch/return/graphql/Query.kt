package org.misarch.`return`.graphql

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.server.operations.Query
import graphql.schema.DataFetchingEnvironment
import org.misarch.common.graphql.authorizedUser
import org.misarch.`return`.graphql.model.Return
import org.misarch.`return`.graphql.model.connection.ReturnConnection
import org.misarch.`return`.graphql.model.connection.ReturnOrder
import org.misarch.`return`.service.ReturnService
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Defines GraphQL queries
 *
 * @property returnService service for returns
 * @property entityTemplate executes the connection queries
 */
@Component
class Query(
    private val returnService: ReturnService,
    private val entityTemplate: R2dbcEntityTemplate
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
        return ReturnConnection(first, skip, null, orderBy, entityTemplate, dfe.authorizedUser)
    }

    @GraphQLDescription("Get a return by id")
    suspend fun `return`(
        @GraphQLDescription("The id of the return")
        id: UUID,
        dfe: DataFetchingEnvironment
    ): Return {
        return returnService.findReturn(id, dfe.authorizedUser).toDTO()
    }
}
