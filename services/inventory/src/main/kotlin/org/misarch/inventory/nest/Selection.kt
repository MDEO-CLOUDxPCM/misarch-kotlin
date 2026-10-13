package org.misarch.inventory.nest

import graphql.schema.DataFetchingEnvironment

/**
 * The names of the fields selected directly under the connection field, including those selected through fragments
 * and inline fragments. FIXED (docs/fixes/inventory.md): the original's `queryKeys` ignored fragments, so a
 * connection queried through a fragment never computed `totalCount` (non-null error) or `nodes`.
 */
fun DataFetchingEnvironment.queryKeys(): List<String> = selectionSet.immediateFields.map { it.name }.distinct()
