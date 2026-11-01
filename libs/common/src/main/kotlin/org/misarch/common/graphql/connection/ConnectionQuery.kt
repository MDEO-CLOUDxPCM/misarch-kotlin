package org.misarch.common.graphql.connection

import io.r2dbc.spi.Row
import io.r2dbc.spi.RowMetadata
import kotlinx.coroutines.reactor.awaitSingle
import org.misarch.common.persistence.Condition
import org.misarch.common.persistence.SqlParameters
import org.springframework.r2dbc.core.DatabaseClient

/**
 * The three SQL statements of a connection over one table (alias `t`), each executed only when its field is resolved:
 * ```
 * nodes:       SELECT t.* FROM <table> t <join> WHERE <condition> ORDER BY <order> LIMIT <first> OFFSET <skip>
 * totalCount:  SELECT COUNT(t.<primaryKey>) FROM <table> t <join> WHERE <condition>
 * hasNextPage: SELECT 1 FROM <table> t <join> WHERE <condition> LIMIT 1 OFFSET <first + skip>   (false if first is null)
 * ```
 * This is the SQL of [BaseConnection] (the Kotlin originals' QueryDSL connection), usable without its GraphQL type:
 * the ports of the former MongoDB services have connection types with other field descriptions and use it from their
 * own `nodes`/`totalCount`/`hasNextPage` field resolvers (docs/PORTING.md §6). Paging arguments are validated by
 * [pagingValidation] (the Rust ports validate them in the parent field resolver and use [PagingValidation.NONE]).
 *
 * @param E the row type ([mapRow])
 * @property databaseClient executes the statements
 * @property table the (rendered) table name
 * @property join optional join clause, appended verbatim; order items without alias then refer to `t`
 * @property conditionOf builds the WHERE condition (null: none); called per statement, after the paging validation
 * @property orderBy SQL order items without the keyword (e.g. `["id ASC"]`)
 * @property first LIMIT (null: no limit, `hasNextPage` is false)
 * @property skip OFFSET (null: 0)
 * @property primaryKey the column counted by `totalCount`
 * @property selection the select list of `nodes` (default `t.*`); with [distinct] a `SELECT DISTINCT` over it
 *   (e.g. `stringValue`): `totalCount` is then `COUNT(DISTINCT <selection>)` and `hasNextPage` probes the distinct rows
 * @property distinct whether the connection lists the distinct values of [selection]
 * @property mapRow maps a row of `SELECT <selection>`
 */
class ConnectionQuery<E : Any>(
    private val databaseClient: DatabaseClient,
    private val table: String,
    private val conditionOf: () -> Condition?,
    private val orderBy: List<String>,
    private val first: Int?,
    private val skip: Int?,
    private val join: String? = null,
    private val primaryKey: String = "id",
    private val pagingValidation: PagingValidation = PagingValidation.NONE,
    private val selection: String = "t.*",
    private val distinct: Boolean = false,
    private val mapRow: (Row, RowMetadata) -> E,
) {

    /** A connection with a fixed [condition]. */
    constructor(
        databaseClient: DatabaseClient,
        table: String,
        condition: Condition?,
        orderBy: List<String>,
        first: Int?,
        skip: Int?,
        join: String? = null,
        primaryKey: String = "id",
        pagingValidation: PagingValidation = PagingValidation.NONE,
        selection: String = "t.*",
        distinct: Boolean = false,
        mapRow: (Row, RowMetadata) -> E,
    ) : this(
        databaseClient, table, { condition }, orderBy, first, skip, join, primaryKey, pagingValidation, selection,
        distinct, mapRow
    )

    /** `SELECT COUNT(t.<primaryKey>) ...`: the number of rows matching the condition (independent of paging). */
    suspend fun totalCount(): Int {
        val params = SqlParameters()
        val counted = if (distinct) "DISTINCT $selection" else "t.$primaryKey"
        val sql = "SELECT COUNT($counted) FROM ${from()}${where(params)}"
        return params.bindTo(databaseClient.sql(sql))
            .map { row -> (row.get(0) as Number).toInt() }
            .one()
            .awaitSingle()
    }

    /** `SELECT t.* ... ORDER BY ... LIMIT first OFFSET skip`: the page. */
    suspend fun nodes(): List<E> {
        pagingValidation.check((skip ?: 0).toLong(), first?.toLong())
        val params = SqlParameters()
        val order = if (orderBy.isEmpty()) "" else " ORDER BY " + orderBy.joinToString(", ") { qualify(it) }
        val limit = if (first != null) " LIMIT $first" else ""
        val sql = "${select()} FROM ${from()}${where(params)}$order$limit OFFSET ${skip ?: 0}"
        return params.bindTo(databaseClient.sql(sql))
            .map { row, metadata -> mapRow(row, metadata) }
            .all()
            .collectList()
            .awaitSingle()
    }

    /** `SELECT 1 ... LIMIT 1 OFFSET first + skip`: whether a row exists after the page (false without [first]). */
    suspend fun hasNextPage(): Boolean {
        if (first == null) {
            return false
        }
        pagingValidation.check(first.toLong() + (skip ?: 0), 1)
        val params = SqlParameters()
        val probe = if (distinct) select() else "SELECT 1"
        val sql = "$probe FROM ${from()}${where(params)} LIMIT 1 OFFSET ${first.toLong() + (skip ?: 0)}"
        return params.bindTo(databaseClient.sql(sql))
            .map { _ -> true }
            .all()
            .hasElements()
            .awaitSingle()
    }

    private fun select(): String = if (distinct) "SELECT DISTINCT $selection" else "SELECT $selection"

    private fun from(): String = "$table t" + (join?.let { " $it" } ?: "")

    private fun where(params: SqlParameters): String {
        val condition = conditionOf() ?: return ""
        return " WHERE " + params.render(condition)
    }

    /** Order columns without alias refer to the main table. */
    private fun qualify(orderItem: String): String =
        if (join != null && orderItem.first().isLetter() && !orderItem.substringBefore(' ').contains('.')) {
            "t.$orderItem"
        } else {
            orderItem
        }
}
