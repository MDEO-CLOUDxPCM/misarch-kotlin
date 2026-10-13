package org.misarch.common.graphql.connection

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.generator.annotations.GraphQLIgnore
import kotlinx.coroutines.reactor.awaitSingle
import org.misarch.common.graphql.AuthorizedUser
import org.misarch.common.persistence.BaseEntity
import org.misarch.common.persistence.Condition
import org.misarch.common.persistence.SqlParameters
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import org.springframework.r2dbc.core.DatabaseClient
import kotlin.reflect.KClass

/**
 * A GraphQL connection (`nodes`, `totalCount`, `hasNextPage`; offset pagination with `first`/`skip`) backed by
 * one table - the replacement for the QueryDSL-based `BaseConnection` of the original services. Generated SQL:
 * ```
 * SELECT t.* FROM <table> t <join> WHERE <predicate AND filter AND authorizedUserFilter> ORDER BY <order>
 *   LIMIT <first> OFFSET <skip>
 * ```
 * The main table has the alias `t`. [join] is appended verbatim (e.g.
 * `INNER JOIN ProductToCategoryEntity j ON j.productId = t.id`); conditions may then use `j.` columns.
 *
 * @param T the GraphQL node type
 * @param D the entity type (table)
 * @property first the maximum number of items to return (null: all)
 * @property skip the number of items to skip
 * @property filter the filter input
 * @property predicate a fixed condition (e.g. `userId = <id>` for a user's addresses)
 * @property orderBy SQL order items (from [BaseOrder.toOrderBy])
 * @property entityTemplate executes the queries and maps rows to [entityClass]
 * @property entityClass the entity class (its table name comes from the R2DBC mapping context)
 * @property authorizedUser the authorized user (for [authorizedUserFilter])
 * @property join optional join clause
 */
@GraphQLIgnore
abstract class BaseConnection<T, D : BaseEntity<T>>(
    private val first: Int?,
    private val skip: Int?,
    private val filter: BaseFilter?,
    private val predicate: Condition?,
    private val orderBy: List<String>,
    private val entityTemplate: R2dbcEntityTemplate,
    private val entityClass: KClass<D>,
    protected val authorizedUser: AuthorizedUser?,
    private val join: String? = null,
) {

    /** The primary key column (used by `totalCount`). */
    protected open val primaryKey: String = "id"

    /**
     * Validation of `first`/`skip` before `nodes`/`hasNextPage` query (default none; ports of the QueryDSL-based
     * Kotlin originals override it with [PagingValidation.QUERYDSL]).
     */
    protected open val pagingValidation: PagingValidation get() = PagingValidation.NONE

    @GraphQLDescription("The total amount of items in this connection")
    suspend fun totalCount(): Int {
        val params = SqlParameters()
        val sql = "SELECT COUNT(t.$primaryKey) FROM ${from()}${where(params)}"
        return bind(entityTemplate.databaseClient.sql(sql), params)
            .map { row -> (row.get(0) as Number).toInt() }
            .one()
            .awaitSingle()
    }

    @GraphQLDescription("The resulting items.")
    open suspend fun nodes(): List<T> {
        pagingValidation.check((skip ?: 0).toLong(), first?.toLong())
        val params = SqlParameters()
        val order = if (orderBy.isEmpty()) "" else " ORDER BY " + orderBy.joinToString(", ") { qualify(it) }
        val limit = if (first != null) " LIMIT $first" else ""
        val sql = "SELECT t.* FROM ${from()}${where(params)}$order$limit OFFSET ${skip ?: 0}"
        val converter = entityTemplate.converter
        return bind(entityTemplate.databaseClient.sql(sql), params)
            .map { row, metadata -> converter.read(entityClass.java, row, metadata) }
            .all()
            .collectList()
            .awaitSingle()
            .map { it.toDTO() }
    }

    @GraphQLDescription("Whether this connection has a next page")
    suspend fun hasNextPage(): Boolean {
        if (first == null) {
            return false
        }
        pagingValidation.check(first.toLong() + (skip ?: 0), 1)
        val params = SqlParameters()
        val sql = "SELECT 1 FROM ${from()}${where(params)} LIMIT 1 OFFSET ${first.toLong() + (skip ?: 0)}"
        return bind(entityTemplate.databaseClient.sql(sql), params)
            .map { _ -> true }
            .all()
            .hasElements()
            .awaitSingle()
    }

    /**
     * Additional condition depending on the authorized user (e.g. only own items for buyers), or null.
     */
    protected open fun authorizedUserFilter(): Condition? = null

    private fun from(): String {
        val table = entityTemplate.converter.mappingContext.getRequiredPersistentEntity(entityClass.java).tableName
        return "${entityTemplate.dataAccessStrategy.toSql(table)} t" +
            (join?.let { " $it" } ?: "")
    }

    private fun where(params: SqlParameters): String {
        val condition = Condition.allOf(predicate, filter?.toCondition(), authorizedUserFilter()) ?: return ""
        return " WHERE " + params.render(condition)
    }

    /** Order columns without alias refer to the main table. */
    private fun qualify(orderItem: String): String =
        if (join != null && orderItem.first().isLetter() && !orderItem.substringBefore(' ').contains('.')) {
            "t.$orderItem"
        } else {
            orderItem
        }

    private fun bind(spec: DatabaseClient.GenericExecuteSpec, params: SqlParameters): DatabaseClient.GenericExecuteSpec =
        params.values.entries.fold(spec) { acc, (name, value) ->
            if (value == null) acc.bindNull(name, Any::class.java) else acc.bind(name, value)
        }
}
