package org.misarch.shoppingcart.graphql.model

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.generator.annotations.GraphQLIgnore
import org.misarch.common.persistence.Condition
import org.misarch.shoppingcart.graphql.model.connection.CommonOrder
import org.misarch.shoppingcart.graphql.model.connection.ShoppingCartItemConnection
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import java.time.OffsetDateTime
import java.util.UUID

@GraphQLDescription("The shopping cart of a user.")
class ShoppingCart(
    private val userId: UUID,
    @property:GraphQLDescription("Timestamp when shopping cart was last updated.")
    val lastUpdatedAt: OffsetDateTime,
) {

    @GraphQLDescription("Retrieves shoppingcart items in shopping cart.")
    fun shoppingcartItems(
        @GraphQLDescription("Describes that the `first` N shoppingcarts should be retrieved.")
        first: Int? = null,
        @GraphQLDescription("Describes how many shoppingcarts should be skipped at the beginning.")
        skip: Int? = null,
        @GraphQLDescription("Specifies the order in which shoppingcarts are retrieved.")
        orderBy: CommonOrder? = null,
        @GraphQLIgnore
        @Autowired
        entityTemplate: R2dbcEntityTemplate,
    ): ShoppingCartItemConnection {
        return ShoppingCartItemConnection(first, skip, Condition.eq("userId", userId), orderBy, entityTemplate)
    }

}
