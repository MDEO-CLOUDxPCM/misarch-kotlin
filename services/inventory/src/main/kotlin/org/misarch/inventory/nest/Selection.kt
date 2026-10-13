package org.misarch.inventory.nest

import graphql.language.Field
import graphql.schema.DataFetchingEnvironment

/**
 * `queryKeys(info)` of the original inventory: names of the **field** selections directly under the first field
 * node (fragments are ignored, aliases resolve to the field name).
 */
fun DataFetchingEnvironment.queryKeys(): List<String> {
    val selectionSet = mergedField.fields.firstOrNull()?.selectionSet ?: return emptyList()
    return selectionSet.selections.filterIsInstance<Field>().map { it.name }
}
