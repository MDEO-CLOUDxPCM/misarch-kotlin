package org.misarch.shoppingcart.graphql

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.server.operations.Query
import graphql.schema.DataFetchingEnvironment
import org.misarch.shoppingcart.graphql.model.ShoppingCartItem
import org.misarch.shoppingcart.service.ShoppingcartService
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Describes GraphQL shopping cart queries (entity resolvers: graphql/federation).
 */
@Component
class Query(
    private val shoppingcartService: ShoppingcartService
) : Query {

    @GraphQLDescription("Retrieves shopping cart item of specific UUID.")
    suspend fun shoppingcartItem(
        @GraphQLDescription("UUID of shoppingcart to retrieve.")
        id: UUID,
        dfe: DataFetchingEnvironment
    ): ShoppingCartItem = shoppingcartService.shoppingcartItem(dfe, id)
}
