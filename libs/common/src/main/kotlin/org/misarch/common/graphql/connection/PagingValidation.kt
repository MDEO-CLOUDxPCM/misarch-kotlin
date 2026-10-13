package org.misarch.common.graphql.connection

/**
 * Validation of the paging arguments of a [BaseConnection] before `nodes` / `hasNextPage` run their query.
 *
 * - [NONE] (default): no validation, the database decides (a negative `LIMIT`/`OFFSET` is a Postgres error,
 *   `first: 0` returns no nodes).
 * - [QUERYDSL]: the behaviour of the QueryDSL-based `BaseConnection` of the original Kotlin services
 *   (`com.querydsl.core.QueryModifiers`): `nodes` checks `offset = skip ?: 0` and then `limit = first`
 *   (`first: 0` fails with `Limit must be greater than 0.`, a negative skip with `Offset must not be negative.`),
 *   `hasNextPage` checks `offset = first + skip` (`first: -1` fails there). The errors are
 *   `IllegalArgumentException`s, i.e. `Exception while fetching data (/x/nodes) : Limit must be greater than 0.`
 *   like in the originals (observed on the original tax service, 2026-10-13).
 */
enum class PagingValidation {
    NONE {
        override fun check(offset: Long, limit: Long?) = Unit
    },
    QUERYDSL {
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
