package org.misarch.catalog.graphql.model.connection

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.generator.federation.directives.ShareableDirective
import org.misarch.catalog.graphql.model.CategoryCharacteristic
import org.misarch.catalog.persistence.model.CategoryCharacteristicEntity
import org.misarch.common.graphql.AuthorizedUser
import org.misarch.common.graphql.connection.BaseConnection
import org.misarch.common.graphql.connection.PagingValidation
import org.misarch.common.graphql.connection.BaseOrder
import org.misarch.common.graphql.connection.BaseOrderField
import org.misarch.common.graphql.connection.OrderDirection
import org.misarch.common.persistence.Condition
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate

/**
 * A GraphQL connection for [CategoryCharacteristic]s.
 *
 * @param first The maximum number of items to return
 * @param skip The number of items to skip
 * @param predicate The predicate to filter the items by
 * @param order The order to sort the items by
 * @param entityTemplate executes the queries
 * @param authorizedUser The authorized user
 * @param join An optional join clause (main table alias `t`)
 */
@GraphQLDescription("A connection to a list of `CategoryCharacteristic` values.")
@ShareableDirective
class CategoryCharacteristicConnection(
    first: Int?,
    skip: Int?,
    predicate: Condition?,
    order: CategoryCharacteristicOrder?,
    entityTemplate: R2dbcEntityTemplate,
    authorizedUser: AuthorizedUser?,
    join: String? = null
) : BaseConnection<CategoryCharacteristic, CategoryCharacteristicEntity>(
    first,
    skip,
    null,
    predicate,
    (order ?: CategoryCharacteristicOrder.DEFAULT).toOrderBy(CategoryCharacteristicOrderField.ID),
    entityTemplate,
    CategoryCharacteristicEntity::class,
    authorizedUser,
    join
) {

    override val pagingValidation: PagingValidation get() = PagingValidation.QUERYDSL
}

@GraphQLDescription("CategoryCharacteristic order fields")
enum class CategoryCharacteristicOrderField(
    override vararg val columns: String
) : BaseOrderField {
    @GraphQLDescription("Order categoryCharacteristics by their id")
    ID("id")
}

@GraphQLDescription("CategoryCharacteristic order")
class CategoryCharacteristicOrder(
    direction: OrderDirection?, field: CategoryCharacteristicOrderField?
) : BaseOrder<CategoryCharacteristicOrderField>(direction, field) {

    companion object {
        val DEFAULT = CategoryCharacteristicOrder(OrderDirection.ASC, CategoryCharacteristicOrderField.ID)
    }
}
