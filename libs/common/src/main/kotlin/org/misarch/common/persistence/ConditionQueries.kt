package org.misarch.common.persistence

import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import kotlin.reflect.KClass

/**
 * Queries over one entity table with a [Condition], for the cases a repository's derived finders cannot express.
 * Inject it into a service; repositories stay interfaces.
 * The main table has the alias `t`, an optional [join] is appended verbatim (`LEFT JOIN XEntity j ON j.xId = t.id`),
 * columns of `t` in [Condition]s and order items may then be written with or without the alias.
 * ```
 * conditionQueries.findAll<DiscountEntity>(
 *     Condition.isNull("t.validUntil") or Condition.gt("t.validUntil", now),
 *     join = "LEFT JOIN DiscountUsageEntity j ON j.discountId = t.id",
 *     orderBy = listOf("t.id ASC"),
 * )
 * ```
 * Generated SQL: `SELECT t.* FROM <table> t <join> WHERE <condition> ORDER BY <orderBy> LIMIT <limit> OFFSET <offset>`
 * (table name from the R2DBC mapping context, rows mapped by the entity converter like a repository).
 * Id lists: [Condition.inArray] (`t.id = ANY(?)`, one array parameter) rather than [Condition.inList].
 */
class ConditionQueries(private val entityTemplate: R2dbcEntityTemplate) {

    /** All rows of [entityClass] matching [condition] (null: all rows). */
    suspend fun <T : Any> findAll(
        entityClass: KClass<T>,
        condition: Condition?,
        join: String? = null,
        orderBy: List<String> = emptyList(),
        limit: Int? = null,
        offset: Long? = null,
        distinct: Boolean = false,
    ): List<T> {
        val params = SqlParameters()
        val order = if (orderBy.isEmpty()) "" else " ORDER BY " + orderBy.joinToString(", ")
        val page = (if (limit != null) " LIMIT $limit" else "") + (if (offset != null) " OFFSET $offset" else "")
        val select = if (distinct) "SELECT DISTINCT t.*" else "SELECT t.*"
        val sql = "$select FROM ${from(entityClass, join)}${where(condition, params)}$order$page"
        val converter = entityTemplate.converter
        return params.bindTo(entityTemplate.databaseClient.sql(sql))
            .map { row, metadata -> converter.read(entityClass.java, row, metadata) }
            .all()
            .collectList()
            .awaitSingle()
    }

    /** The first row (by [orderBy]) of [entityClass] matching [condition], or null. */
    suspend fun <T : Any> findFirst(
        entityClass: KClass<T>,
        condition: Condition?,
        join: String? = null,
        orderBy: List<String> = emptyList(),
    ): T? = findAll(entityClass, condition, join, orderBy, limit = 1).firstOrNull()

    /** `SELECT COUNT(<countExpression>) ...`: the number of rows matching [condition]. */
    suspend fun <T : Any> count(
        entityClass: KClass<T>,
        condition: Condition?,
        join: String? = null,
        countExpression: String = "t.id",
    ): Long {
        val params = SqlParameters()
        val sql = "SELECT COUNT($countExpression) FROM ${from(entityClass, join)}${where(condition, params)}"
        return params.bindTo(entityTemplate.databaseClient.sql(sql))
            .map { row -> (row.get(0) as Number).toLong() }
            .one()
            .awaitSingle()
    }

    /** `SELECT 1 ... LIMIT 1`: whether a row matches [condition]. */
    suspend fun <T : Any> exists(entityClass: KClass<T>, condition: Condition?, join: String? = null): Boolean {
        val params = SqlParameters()
        val sql = "SELECT 1 FROM ${from(entityClass, join)}${where(condition, params)} LIMIT 1"
        return params.bindTo(entityTemplate.databaseClient.sql(sql))
            .map { _ -> true }
            .all()
            .hasElements()
            .awaitSingle()
    }

    /** `DELETE FROM <table> t WHERE <condition>`; returns the number of deleted rows. */
    suspend fun <T : Any> delete(entityClass: KClass<T>, condition: Condition): Long {
        val params = SqlParameters()
        val sql = "DELETE FROM ${from(entityClass, null)}${where(condition, params)}"
        return params.bindTo(entityTemplate.databaseClient.sql(sql))
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    /**
     * `INSERT` of [entity] with all its columns, also when its id is already set (a repository's `save` would
     * `UPDATE` an entity with a non-null id). For entities whose id is created by the service or comes from another
     * service; replicas with only an id use a repository `@Query("INSERT ... ON CONFLICT (id) DO NOTHING")`.
     */
    suspend fun <T : Any> insert(entity: T): T = entityTemplate.insert(entity).awaitSingle()

    suspend inline fun <reified T : Any> findAll(
        condition: Condition?,
        join: String? = null,
        orderBy: List<String> = emptyList(),
        limit: Int? = null,
        offset: Long? = null,
        distinct: Boolean = false,
    ): List<T> = findAll(T::class, condition, join, orderBy, limit, offset, distinct)

    suspend inline fun <reified T : Any> findFirst(
        condition: Condition?,
        join: String? = null,
        orderBy: List<String> = emptyList(),
    ): T? = findFirst(T::class, condition, join, orderBy)

    suspend inline fun <reified T : Any> count(
        condition: Condition?,
        join: String? = null,
        countExpression: String = "t.id",
    ): Long = count(T::class, condition, join, countExpression)

    suspend inline fun <reified T : Any> exists(condition: Condition?, join: String? = null): Boolean =
        exists(T::class, condition, join)

    suspend inline fun <reified T : Any> delete(condition: Condition): Long = delete(T::class, condition)

    /** The rendered table name of [entityClass] (as used in `FROM`). */
    fun tableName(entityClass: KClass<*>): String {
        val table = entityTemplate.converter.mappingContext.getRequiredPersistentEntity(entityClass.java).tableName
        return entityTemplate.dataAccessStrategy.toSql(table)
    }

    private fun from(entityClass: KClass<*>, join: String?): String =
        "${tableName(entityClass)} t" + (join?.let { " $it" } ?: "")

    private fun where(condition: Condition?, params: SqlParameters): String =
        if (condition == null) "" else " WHERE " + params.render(condition)
}
