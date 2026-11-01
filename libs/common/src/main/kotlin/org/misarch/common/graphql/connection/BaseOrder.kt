package org.misarch.common.graphql.connection

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.generator.annotations.GraphQLIgnore

/**
 * A GraphQL order input, consisting of a field and a direction. Subclass it per connection:
 * ```
 * @GraphQLDescription("User address order")
 * class UserAddressOrder(direction: OrderDirection?, field: UserAddressOrderField?) :
 *     BaseOrder<UserAddressOrderField>(direction, field)
 * ```
 *
 * @property direction the direction to order by (default ASC)
 * @property field the field to order by (default: the connection's default field)
 */
@GraphQLIgnore
abstract class BaseOrder<T : BaseOrderField>(
    @property:GraphQLDescription("The direction to order by")
    val direction: OrderDirection?,
    @property:GraphQLDescription("The field to order by")
    val field: T?
) {

    /**
     * SQL `ORDER BY` items (without the keyword), e.g. `["id ASC"]`.
     *
     * @param defaultField the field if none is given
     * @param tieBreak order items appended after the field's columns (whose column is not ordered already), giving a
     *   fixed order of ties independent of [direction], e.g. `listOf("id DESC")`
     */
    fun toOrderBy(defaultField: T, tieBreak: List<String> = emptyList()): List<String> {
        val dir = (direction ?: OrderDirection.ASC).sql
        val columns = (field ?: defaultField).columns
        return columns.map { "$it $dir" } + tieBreak.filter { it.substringBefore(' ') !in columns }
    }
}
