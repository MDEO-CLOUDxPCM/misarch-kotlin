package org.misarch.wishlist.graphql.model

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.generator.annotations.GraphQLIgnore
import com.expediagroup.graphql.generator.federation.directives.FieldSet
import com.expediagroup.graphql.generator.federation.directives.KeyDirective
import graphql.schema.DataFetchingEnvironment
import org.misarch.common.graphql.authorizedUser
import org.misarch.common.persistence.Condition
import org.misarch.wishlist.graphql.model.connection.WishlistConnection
import org.misarch.wishlist.graphql.model.connection.WishlistOrder
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import java.util.UUID

@GraphQLDescription("Type of a user owning wishlists.")
@KeyDirective(fields = FieldSet("id"))
class User(
    @property:GraphQLDescription("UUID of the user.")
    val id: UUID
) {

    @GraphQLDescription("Retrieves wishlists of user.")
    fun wishlists(
        @GraphQLDescription("Describes that the `first` N wishlists should be retrieved.")
        first: Int? = null,
        @GraphQLDescription("Describes how many wishlists should be skipped at the beginning.")
        skip: Int? = null,
        @GraphQLDescription("Specifies the order in which wishlists are retrieved.")
        orderBy: WishlistOrder? = null,
        @GraphQLIgnore
        @Autowired
        entityTemplate: R2dbcEntityTemplate,
        dfe: DataFetchingEnvironment
    ): WishlistConnection {
        if (dfe.authorizedUser.id != id) {
            dfe.authorizedUser.checkIsEmployee()
        }
        return WishlistConnection(first, skip, Condition.eq("userId", id), orderBy, entityTemplate)
    }

}
