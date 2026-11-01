package org.misarch.common.graphql.connection

/**
 * Validation of the paging arguments of a connection before `nodes` / `hasNextPage` run their query. The errors are
 * `IllegalArgumentException`s, i.e. `Exception while fetching data (/x/nodes) : Limit must be greater than 0.`.
 *
 * - [NONE]: no validation, the database decides (a negative `LIMIT`/`OFFSET` is a Postgres error, `first: 0`
 *   returns no nodes).
 * - [NON_NEGATIVE]: negative `skip`/`first` are rejected (`Offset must not be negative.`, `Limit must not be
 *   negative.`), `first: 0` is allowed and returns no nodes.
 * - [POSITIVE_LIMIT] (default of [BaseConnection] and [connectionQuery]): `nodes` checks `offset = skip ?: 0` and
 *   then `limit = first` (`first: 0` fails with `Limit must be greater than 0.`, a negative skip with
 *   `Offset must not be negative.`); `hasNextPage` checks `offset = first + skip` (`first: -1` fails there).
 */
enum class PagingValidation {
    NONE {
        override fun check(offset: Long, limit: Long?) = Unit
    },
    NON_NEGATIVE {
        override fun check(offset: Long, limit: Long?) {
            require(offset >= 0) { "Offset must not be negative." }
            if (limit != null) {
                require(limit >= 0) { "Limit must not be negative." }
            }
        }
    },
    POSITIVE_LIMIT {
        override fun check(offset: Long, limit: Long?) {
            require(offset >= 0) { "Offset must not be negative." }
            if (limit != null) {
                require(limit > 0) { "Limit must be greater than 0." }
            }
        }
    };

    /** Throws `IllegalArgumentException` if [offset] / [limit] (null: no limit) are not accepted. */
    abstract fun check(offset: Long, limit: Long?)
}
