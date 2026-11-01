package org.misarch.common.graphql.connection

/**
 * A GraphQL order input: a field and a direction, both optional. Each order input of a service implements it, with
 * the service's `OrderDirection` and the descriptions of its schema:
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
        val dir = direction?.sql ?: "ASC"
        val columns = (field ?: defaultField).columns
        return columns.map { "$it $dir" } + tieBreak.filter { it.substringBefore(' ') !in columns }
    }
}
