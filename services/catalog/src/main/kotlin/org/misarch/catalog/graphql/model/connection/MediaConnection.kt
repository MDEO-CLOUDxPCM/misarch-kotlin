package org.misarch.catalog.graphql.model.connection

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.generator.federation.directives.ShareableDirective
import org.misarch.catalog.graphql.model.Media
import org.misarch.catalog.persistence.model.MediaEntity
import org.misarch.common.graphql.AuthorizedUser
import org.misarch.common.graphql.connection.BaseConnection
import org.misarch.common.graphql.connection.CommonOrder
import org.misarch.common.graphql.connection.CommonOrderField
import org.misarch.common.persistence.Condition
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate

/**
 * A GraphQL connection for [Media]s.
 *
 * @param first The maximum number of items to return
 * @param skip The number of items to skip
 * @param predicate The predicate to filter the items by
 * @param order The order to sort the items by
 * @param entityTemplate executes the queries
 * @param authorizedUser The authorized user
 * @param join An optional join clause (main table alias `t`)
 */
@GraphQLDescription("A connection to a list of `Media` values.")
@ShareableDirective
class MediaConnection(
    first: Int?,
    skip: Int?,
    predicate: Condition?,
    order: CommonOrder?,
    entityTemplate: R2dbcEntityTemplate,
    authorizedUser: AuthorizedUser?,
    join: String? = null
) : BaseConnection<Media, MediaEntity>(
    first,
    skip,
    null,
    predicate,
    (order ?: CommonOrder.DEFAULT).toOrderBy(CommonOrderField.ID),
    entityTemplate,
    MediaEntity::class,
    authorizedUser,
    join
) {

}
