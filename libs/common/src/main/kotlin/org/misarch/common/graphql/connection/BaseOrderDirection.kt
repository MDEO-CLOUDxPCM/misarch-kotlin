package org.misarch.common.graphql.connection

/**
 * A GraphQL order direction enum whose constants carry the SQL keyword. Each service declares its own enum
 * `OrderDirection` (with the descriptions of its schema) implementing this interface.
 */
interface BaseOrderDirection {
    /** `ASC` or `DESC`. */
    val sql: String
}
