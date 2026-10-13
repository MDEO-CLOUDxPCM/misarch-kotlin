package org.misarch.user.graphql

import com.expediagroup.graphql.generator.execution.OptionalInput

/**
 * Executes [block] with the value if `this is OptionalInput.Defined`
 *
 * @param block executed if input is defined
 */
inline fun <T> OptionalInput<T>.ifPresent(block: (T?) -> Unit) {
    if (this is OptionalInput.Defined) {
        block(this.value)
    }
}
