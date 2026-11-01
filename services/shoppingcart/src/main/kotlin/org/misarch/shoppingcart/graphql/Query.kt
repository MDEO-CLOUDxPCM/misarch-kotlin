package org.misarch.shoppingcart.graphql

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.server.operations.Query
import graphql.schema.DataFetchingEnvironment
import org.misarch.common.graphql.authorizedUser
import org.misarch.common.graphql.load
import org.misarch.shoppingcart.graphql.dataloader.ShoppingCartItemDataLoader
import org.misarch.shoppingcart.graphql.model.ShoppingCartItem
import org.springframework.stereotype.Component
import java.util.UUID
import java.util.concurrent.CompletableFuture

/**
 * Defines GraphQL queries (users and items are also resolved as federation entities, see graphql/federation)
 */
@Component
class Query : Query {

    @GraphQLDescription("Retrieves shopping cart item of specific UUID.")
    fun shoppingcartItem(
        @GraphQLDescription("UUID of shoppingcart to retrieve.")
        id: UUID,
        dfe: DataFetchingEnvironment
    ): CompletableFuture<ShoppingCartItem> {
        return dfe.load(ShoppingCartItemDataLoader::class, id).thenApply {
            val authorizedUser = dfe.authorizedUser
            if (it.userId != authorizedUser.id) {
                authorizedUser.checkIsEmployee()
            }
            it
        }
    }

}
