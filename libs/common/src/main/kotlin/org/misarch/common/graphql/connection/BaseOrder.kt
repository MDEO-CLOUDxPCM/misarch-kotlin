package org.misarch.common.graphql.connection

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.generator.annotations.GraphQLIgnore

/**
 * A GraphQL order input: a field and a direction, both optional. [BaseOrder] implements it with the common
 * [OrderDirection]; an order input whose schema has its own direction enum or other field descriptions implements
 * this interface directly:
 * ```
 * @GraphQLDescription("Specifies the order of reviews.")
 * class ReviewOrder(
 *     @property:GraphQLDescription("Order direction of reviews.")
 *     override val direction: OrderDirection?,
 *     @property:GraphQLDescription("Field that reviews should be ordered by.")
 *     override val field: ReviewOrderField?
 * ) : Ordering<ReviewOrderField>
 * ```
 *
 * @param F the order field enum
 */
interface Ordering<F : BaseOrderField> {

    /** The direction to order by (default ASC). */
    val direction: BaseOrderDirection?

    /** The field to order by (default: the connection's default field). */
    val field: F?

    /**
     * SQL `ORDER BY` items (without the keyword), e.g. `["id ASC"]`.
     *
     * @param defaultField the field if none is given
     * @param tieBreak order items appended after the field's columns (whose column is not ordered already), giving a
     *   fixed order of ties independent of [direction], e.g. `listOf("id DESC")`
     */
    fun toOrderBy(defaultField: F, tieBreak: List<String> = emptyList()): List<String> {
        val dir = direction?.sql ?: OrderDirection.ASC.sql
        val columns = (field ?: defaultField).columns
        return columns.map { "$it $dir" } + tieBreak.filter { it.substringBefore(' ') !in columns }
    }
}

/**
 * An order input with the common [OrderDirection] and field descriptions. Subclass it per connection:
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
    override val direction: OrderDirection?,
    @property:GraphQLDescription("The field to order by")
    override val field: T?
) : Ordering<T>
