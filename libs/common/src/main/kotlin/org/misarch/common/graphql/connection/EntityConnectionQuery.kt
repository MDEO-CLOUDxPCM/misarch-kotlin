package org.misarch.common.graphql.connection

import org.misarch.common.persistence.Condition
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import kotlin.reflect.KClass

/**
 * The [ConnectionQuery] over the table of [entityClass], rows mapped by the entity converter (like [BaseConnection]).
 * For connection types whose SDL differs from [BaseConnection]'s (e.g. nullable `nodes`, other descriptions):
 * ```
 * private val query = entityTemplate.connectionQuery(ProductItemEntity::class, condition, orderBy, first, skip)
 * suspend fun nodes(): List<ProductItem>? = query.nodes().map { it.toDTO() }
 * ```
 *
 * @param entityClass the entity class (its table name comes from the R2DBC mapping context)
 * @param condition the WHERE condition (null: none)
 * @param orderBy SQL order items (e.g. from [BaseOrder.toOrderBy])
 * @param first LIMIT (null: no limit)
 * @param skip OFFSET (null: 0)
 * @param join optional join clause, appended verbatim
 * @param pagingValidation validation of [first] / [skip] (default: the one of [BaseConnection])
 */
fun <E : Any> R2dbcEntityTemplate.connectionQuery(
    entityClass: KClass<E>,
    condition: Condition?,
    orderBy: List<String>,
    first: Int?,
    skip: Int?,
    join: String? = null,
    pagingValidation: PagingValidation = PagingValidation.QUERYDSL,
): ConnectionQuery<E> {
    val table = converter.mappingContext.getRequiredPersistentEntity(entityClass.java).tableName
    return ConnectionQuery(
        databaseClient = databaseClient,
        table = dataAccessStrategy.toSql(table),
        condition = condition,
        orderBy = orderBy,
        first = first,
        skip = skip,
        join = join,
        pagingValidation = pagingValidation,
    ) { row, metadata -> converter.read(entityClass.java, row, metadata) }
}
