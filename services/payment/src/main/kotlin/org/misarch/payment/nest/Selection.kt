package org.misarch.payment.nest

import graphql.language.Field
import graphql.language.FragmentSpread
import graphql.schema.DataFetchingEnvironment
import org.misarch.common.nest.JsError

/**
 * `queryKeys(info)` of the original payment service: `selections.map(item => item.name.value)` over **all**
 * selections directly under the first field node. A fragment spread contributes its fragment name; an inline
 * fragment has no name and throws `TypeError: Cannot read properties of undefined (reading 'value')` (quirk).
 */
fun DataFetchingEnvironment.queryKeys(): List<String> {
    val selectionSet = mergedField.fields.firstOrNull()?.selectionSet
        ?: throw JsError("Cannot read properties of undefined (reading 'selections')")
    return selectionSet.selections.map { selection ->
        when (selection) {
            is Field -> selection.name
            is FragmentSpread -> selection.name
            else -> throw JsError("Cannot read properties of undefined (reading 'value')")
        }
    }
}
