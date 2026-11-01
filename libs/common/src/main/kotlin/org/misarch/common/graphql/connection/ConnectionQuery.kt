package org.misarch.common.graphql.connection

import io.r2dbc.spi.Row
import io.r2dbc.spi.RowMetadata
import kotlinx.coroutines.reactor.awaitSingle
import org.misarch.common.persistence.Condition
import org.misarch.common.persistence.SqlParameters
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import org.springframework.r2dbc.core.DatabaseClient
import kotlin.reflect.KClass

/**
 * The statements of a connection over the table of [entityClass] (alias `t`), rows mapped by the entity converter.
 * [BaseConnection] is built on it; a connection type whose SDL differs from [BaseConnection]'s (nullable `nodes`,
 * other field descriptions, distinct values) declares its three fields itself and delegates to this query:
 * ```
 * private val query = entityTemplate.connectionQuery(ProductItemEntity::class, condition, orderBy, first, skip)
 * suspend fun nodes(): List<ProductItem>? = query.nodes().map { it.toDTO() }
 * ```
 * See [ConnectionQuery.distinctValues] for a connection over the distinct values of one column.
 *
 * @param entityClass the entity class (its table name comes from the R2DBC mapping context)
 * @param condition the WHERE condition (null: none)
 * @param orderBy SQL order items (e.g. from [BaseOrder.toOrderBy])
 * @param first LIMIT (null: no limit, `hasNextPage` is false)
 * @param skip OFFSET (null: 0)
 * @param join optional join clause, appended verbatim (e.g. `INNER JOIN ProductToCategoryEntity j ON j.categoryId = t.id`)
 * @param primaryKey the column counted by `totalCount`
 * @param pagingValidation validation of [first] / [skip] (default: [PagingValidation.QUERYDSL], like [BaseConnection])
 */
fun <E : Any> R2dbcEntityTemplate.connectionQuery(
    entityClass: KClass<E>,
    condition: Condition?,
    orderBy: List<String>,
    first: Int?,
    skip: Int?,
    join: String? = null,
    primaryKey: String = "id",
    pagingValidation: PagingValidation = PagingValidation.QUERYDSL,
): ConnectionQuery<E> = connectionQuery(entityClass, { condition }, orderBy, first, skip, join, primaryKey, pagingValidation)

/** [connectionQuery] with a condition that is built per statement, after the paging validation ([BaseConnection]). */
internal fun <E : Any> R2dbcEntityTemplate.connectionQuery(
    entityClass: KClass<E>,
    conditionOf: () -> Condition?,
    orderBy: List<String>,
    first: Int?,
    skip: Int?,
    join: String?,
    primaryKey: String,
    pagingValidation: PagingValidation,
): ConnectionQuery<E> {
    val table = converter.mappingContext.getRequiredPersistentEntity(entityClass.java).tableName
    return ConnectionQuery(
        databaseClient = databaseClient,
        table = dataAccessStrategy.toSql(table),
        conditionOf = conditionOf,
        orderBy = orderBy,
        first = first,
        skip = skip,
        join = join,
        primaryKey = primaryKey,
        pagingValidation = pagingValidation,
    ) { row, metadata -> converter.read(entityClass.java, row, metadata) }
}

/**
 * The three SQL statements of a connection, each executed only when its field is resolved:
 * ```
 * nodes:       SELECT t.* FROM <table> t <join> WHERE <condition> ORDER BY <order> LIMIT <first> OFFSET <skip>
 * totalCount:  SELECT COUNT(t.<primaryKey>) FROM <table> t <join> WHERE <condition>
 * hasNextPage: SELECT 1 FROM <table> t <join> WHERE <condition> LIMIT 1 OFFSET <first + skip>   (false if first is null)
 * ```
 * Created by [connectionQuery] (docs/PORTING.md §6). `nodes`/`hasNextPage` first check the paging arguments
 * ([PagingValidation]).
 *
 * @param E the row type ([mapRow])
 */
class ConnectionQuery<E : Any> internal constructor(
    private val databaseClient: DatabaseClient,
    private val table: String,
    private val conditionOf: () -> Condition?,
    private val orderBy: List<String>,
    private val first: Int?,
    private val skip: Int?,
    private val join: String?,
    private val primaryKey: String,
    private val pagingValidation: PagingValidation,
    private val selection: String = "t.*",
    private val distinct: Boolean = false,
    private val mapRow: (Row, RowMetadata) -> E,
) {

    /**
     * The same query over the distinct values of [column] instead of the rows: `nodes` is
     * `SELECT DISTINCT <column> ...` (the order items must then refer to [column]), `totalCount` is
     * `COUNT(DISTINCT <column>)` and `hasNextPage` probes the distinct values.
     *
     * @param column the column (e.g. `stringValue`)
     * @param valueClass the type of the column's values
     */
    fun <V : Any> distinctValues(column: String, valueClass: KClass<V>): ConnectionQuery<V> = ConnectionQuery(
        databaseClient, table, conditionOf, orderBy, first, skip, join, primaryKey, pagingValidation,
        selection = column,
        distinct = true,
    ) { row, _ -> row.get(0, valueClass.java)!! }

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
