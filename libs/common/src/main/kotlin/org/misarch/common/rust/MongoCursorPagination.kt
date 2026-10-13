package org.misarch.common.rust

/**
 * `mongodb-cursor-pagination` 0.3.2 `PaginatedCursor::find` as the Rust originals use it (no cursors):
 * - limit `L = first ?: 25` (DEFAULT_LIMIT), the query fetches `L + 1` rows,
 * - the library appends `_id: -1` as tiebreak to the sort unless the sort is on `_id` (the caller builds the ORDER BY),
 * - `totalCount` = count of the filter (independent of skip/first); 0 → empty page without a find,
 * - `skip` 0/absent: `hasNextPage = rows > L`; `skip > 0`: `hasNextPage = rows + skip < total`
 *   (**off-by-one**: with exactly `L + 1` rows remaining, all `L + 1` are returned and `hasNextPage` is false),
 * - if `hasNextPage`, the last fetched row is dropped.
 */
object MongoCursorPagination {

    const val DEFAULT_LIMIT: Long = 25

    data class Page<T>(val nodes: List<T>, val hasNextPage: Boolean, val totalCount: Long)

    /**
     * @param count counts the matching documents
     * @param fetch fetches rows in the final order: `offset` (null = no skip) and `limit`
     */
    suspend fun <T> find(
        first: Long?,
        skip: Long?,
        count: suspend () -> Long,
        fetch: suspend (offset: Long?, limit: Long) -> List<T>,
    ): Page<T> {
        val limit = first ?: DEFAULT_LIMIT
        val total = count()
        if (total == 0L) return Page(emptyList(), false, 0)
        val skipValue = skip ?: 0L
        val rows: MutableList<T>
        val hasMore: Boolean
        if (skipValue == 0L) {
            rows = fetch(null, limit + 1).toMutableList()
            hasMore = rows.size.toLong() > limit
        } else {
            rows = fetch(skipValue, limit + 1).toMutableList()
            hasMore = rows.size.toLong() + skipValue < total
        }
        if (hasMore && rows.isNotEmpty()) rows.removeAt(rows.size - 1)
        return Page(rows, hasMore, total)
    }
}
