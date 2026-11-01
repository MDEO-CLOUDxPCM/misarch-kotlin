package org.misarch.common.graphql

import com.expediagroup.graphql.generator.execution.OptionalInput

/**
 * Executes [block] with the value if the input was given (`this is OptionalInput.Defined`, also for an explicit
 * `null`); does nothing for an omitted input.
 */
inline fun <T> OptionalInput<T>.ifPresent(block: (T?) -> Unit) {
    if (this is OptionalInput.Defined) {
        block(this.value)
    }
}
