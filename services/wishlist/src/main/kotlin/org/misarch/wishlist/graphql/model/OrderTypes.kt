package org.misarch.wishlist.graphql.model

import com.expediagroup.graphql.generator.annotations.GraphQLDescription

@GraphQLDescription("GraphQL order direction.")
enum class OrderDirection {
    @GraphQLDescription("Ascending order direction.")
    ASC,

    @GraphQLDescription("Descending order direction.")
    DESC
}

@GraphQLDescription("Describes the fields that a foreign types can be ordered by.")
enum class CommonOrderField {
    @GraphQLDescription("Orders by \"id\".")
    ID
}

@GraphQLDescription("Specifies the order of foreign types.")
class CommonOrderInput(
    @property:GraphQLDescription("Order direction of wishlists.")
    val direction: OrderDirection? = null,
    @property:GraphQLDescription("Field that wishlists should be ordered by.")
    val field: CommonOrderField? = null
)

/**
 * @property column the column of `WishlistEntity` (the Mongo field of the original in brackets)
 */
@GraphQLDescription("Describes the fields that a wishlist can be ordered by.")
enum class WishlistOrderField(internal val column: String) {
    @GraphQLDescription("Orders by \"id\".")
    ID("id"), // _id

    @GraphQLDescription("Orders by \"user_id\".")
    USER_ID("userId"), // user._id

    @GraphQLDescription("Orders by \"name\".")
    NAME("name COLLATE \"C\""), // MongoDB compares strings bytewise (no collation)

    @GraphQLDescription("Orders by \"created_at\".")
    CREATED_AT("createdAt"),

    @GraphQLDescription("Orders by \"last_updated_at\".")
    LAST_UPDATED_AT("lastUpdatedAt")
}

@GraphQLDescription("Specifies the order of wishlists.")
class WishlistOrderInput(
    @property:GraphQLDescription("Order direction of wishlists.")
    val direction: OrderDirection? = null,
    @property:GraphQLDescription("Field that wishlists should be ordered by.")
    val field: WishlistOrderField? = null
)
