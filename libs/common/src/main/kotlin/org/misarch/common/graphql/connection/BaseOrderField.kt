package org.misarch.common.graphql.connection

/**
 * A GraphQL order field enum. Implement it with an enum whose constants carry the SQL columns to order by:
 * ```
 * enum class UserAddressOrderField(override vararg val columns: String) : BaseOrderField {
 *     @GraphQLDescription("Order addresss by their id")
 *     ID("id"),
 * }
 * ```
 */
interface BaseOrderField {
    /** The columns (or SQL expressions) to order by, most significant first. */
    val columns: Array<out String>
}
