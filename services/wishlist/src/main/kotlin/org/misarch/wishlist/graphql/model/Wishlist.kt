package org.misarch.wishlist.graphql.model

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.generator.annotations.GraphQLIgnore
import com.expediagroup.graphql.generator.federation.directives.FieldSet
import com.expediagroup.graphql.generator.federation.directives.KeyDirective
import org.misarch.common.persistence.Condition
import org.misarch.wishlist.graphql.model.connection.CommonOrderInput
import org.misarch.wishlist.graphql.model.connection.ProductVariantConnection
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import java.time.OffsetDateTime
import java.util.UUID

@GraphQLDescription("The wishlist of a user.")
@KeyDirective(fields = FieldSet("id"))
class Wishlist(
    @property:GraphQLDescription("Wishlist UUID.")
    val id: UUID,
    @property:GraphQLDescription("User.")
    val user: User,
    @property:GraphQLDescription("Name of wishlist.")
    val name: String,
    @property:GraphQLDescription("Timestamp when wishlist was created.")
    val createdAt: OffsetDateTime,
    @property:GraphQLDescription("Timestamp when wishlist was last updated.")
    val lastUpdatedAt: OffsetDateTime,
) {

    @GraphQLDescription("Retrieves product variants.")
    fun productVariants(
        @GraphQLDescription("Describes that the `first` N product variants should be retrieved.")
        first: Int? = null,
        @GraphQLDescription("Describes how many product variants should be skipped at the beginning.")
        skip: Int? = null,
        @GraphQLDescription("Specifies the order in which product variants are retrieved.")
        orderBy: CommonOrderInput? = null,
        @GraphQLIgnore
        @Autowired
        entityTemplate: R2dbcEntityTemplate,
    ): ProductVariantConnection {
        return ProductVariantConnection(
            first, skip, Condition.eq("j.wishlistId", id), orderBy, entityTemplate,
            "INNER JOIN WishlistToProductVariantEntity j ON j.productVariantId = t.id"
        )
    }

}
