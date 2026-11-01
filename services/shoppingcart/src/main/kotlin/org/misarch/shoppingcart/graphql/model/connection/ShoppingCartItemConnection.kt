package org.misarch.shoppingcart.graphql.model.connection

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.generator.federation.directives.ShareableDirective
import org.misarch.common.graphql.connection.BaseConnection
import org.misarch.common.graphql.connection.BaseOrderField
import org.misarch.common.graphql.connection.PagingValidation
import org.misarch.common.persistence.Condition
import org.misarch.shoppingcart.graphql.model.ShoppingCartItem
import org.misarch.shoppingcart.persistence.model.ShoppingCartItemEntity
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate

/**
 * A GraphQL connection for [ShoppingCartItem]s.
 *
 * @param first The maximum number of items to return
 * @param skip The number of items to skip
 * @param predicate The predicate to filter the items by
 * @param order The order to sort the items by
 * @param entityTemplate executes the queries
 */
@GraphQLDescription("A connection of shopping cart items.")
@ShareableDirective
class ShoppingCartItemConnection(
    first: Int?,
    skip: Int?,
    predicate: Condition?,
    order: CommonOrderInput?,
    entityTemplate: R2dbcEntityTemplate,
) : BaseConnection<ShoppingCartItem, ShoppingCartItemEntity>(
    first,
    skip,
    null,
    predicate,
    (order ?: CommonOrderInput.DEFAULT).toOrderBy(),
    entityTemplate,
    ShoppingCartItemEntity::class,
    null,
) {

    /** `first: 0` is allowed (an empty page), negative arguments are errors */
    override val pagingValidation: PagingValidation get() = PagingValidation.NON_NEGATIVE

    @GraphQLDescription("The resulting entities.")
    override suspend fun nodes(): List<ShoppingCartItem> = super.nodes()

    @GraphQLDescription("Whether this connection has a next page.")
    override suspend fun hasNextPage(): Boolean = super.hasNextPage()

    @GraphQLDescription("The total amount of items in this connection.")
    override suspend fun totalCount(): Int = super.totalCount()

}

@GraphQLDescription("GraphQL order direction.")
enum class OrderDirection(internal val sql: String) {
    @GraphQLDescription("Ascending order direction.")
    ASC("ASC"),

    @GraphQLDescription("Descending order direction.")
    DESC("DESC")
}

@GraphQLDescription("Describes the fields that a foreign types can be ordered by.")
enum class CommonOrderField(override vararg val columns: String) : BaseOrderField {
    @GraphQLDescription("Orders by \"id\".")
    ID("id")
}

@GraphQLDescription("Specifies the order of foreign types.")
class CommonOrderInput(
    @property:GraphQLDescription("Order direction of shoppingcarts.")
    val direction: OrderDirection? = null,
    @property:GraphQLDescription("Field that shoppingcarts should be ordered by.")
    val field: CommonOrderField? = null
) {

    /** SQL order items, e.g. `["id ASC"]` */
    fun toOrderBy(): List<String> {
        val direction = (direction ?: OrderDirection.ASC).sql
        return (field ?: CommonOrderField.ID).columns.map { "$it $direction" }
    }

    companion object {
        val DEFAULT = CommonOrderInput(OrderDirection.ASC, CommonOrderField.ID)
    }
}
