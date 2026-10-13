package org.misarch.common.graphql.connection

import org.misarch.common.persistence.Condition

/**
 * Base interface of all filter inputs.
 */
interface BaseFilter {

    /** The filter as SQL condition, or null if the filter is empty. */
    fun toCondition(): Condition?
}
