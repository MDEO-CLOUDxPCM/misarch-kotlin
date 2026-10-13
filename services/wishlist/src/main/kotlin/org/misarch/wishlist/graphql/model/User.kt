package org.misarch.wishlist.graphql.model

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.generator.annotations.GraphQLIgnore
import com.expediagroup.graphql.generator.federation.directives.FieldSet
import com.expediagroup.graphql.generator.federation.directives.KeyDirective
import graphql.schema.DataFetchingEnvironment
import org.misarch.wishlist.service.WishlistService
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID

@GraphQLDescription("Type of a user owning wishlists.")
@KeyDirective(fields = FieldSet("id"))
class User(
    @property:GraphQLDescription("UUID of the user.")
    val id: UUID
) {

    @GraphQLDescription("Retrieves wishlists of user.")
    suspend fun wishlists(
        @GraphQLDescription("Describes that the `first` N wishlists should be retrieved.")
        first: Int? = null,
        @GraphQLDescription("Describes how many wishlists should be skipped at the beginning.")
        skip: Int? = null,
        @GraphQLDescription("Specifies the order in which wishlists are retrieved.")
        orderBy: WishlistOrderInput? = null,
        @GraphQLIgnore
        @Autowired
        wishlistService: WishlistService,
        dfe: DataFetchingEnvironment
    ): WishlistConnection = wishlistService.wishlistsOfUser(dfe, id, first, skip, orderBy)
}
