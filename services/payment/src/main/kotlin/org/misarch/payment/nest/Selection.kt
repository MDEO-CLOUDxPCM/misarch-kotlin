package org.misarch.payment.nest

import graphql.schema.DataFetchingEnvironment

/**
 * `queryKeys(info)`: the names of the fields selected directly under the connection field. Fix P6
 * (docs/fixes/payment.md): fields selected through fragment spreads and inline fragments count as well (the original
 * mapped every selection to `selection.name.value`, so inline fragments threw a TypeError and fragment spreads
 * contributed only the fragment name).
 */
fun DataFetchingEnvironment.queryKeys(): List<String> = selectionSet.immediateFields.map { it.name }.distinct()
