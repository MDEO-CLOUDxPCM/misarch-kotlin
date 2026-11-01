package org.misarch.common.graphql.connection

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.generator.annotations.GraphQLIgnore
import org.misarch.common.graphql.AuthorizedUser
import org.misarch.common.persistence.BaseEntity
import org.misarch.common.persistence.Condition
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import kotlin.reflect.KClass

/**
 * A GraphQL connection (`nodes`, `totalCount`, `hasNextPage`; offset pagination with `first`/`skip`) backed by
 * one table - the replacement for the QueryDSL-based `BaseConnection` of the original services. Generated SQL:
 * ```
 * SELECT t.* FROM <table> t <join> WHERE <predicate AND filter AND authorizedUserFilter> ORDER BY <order>
 *   LIMIT <first> OFFSET <skip>
 * ```
 * (the statements are [ConnectionQuery]'s, each runs only when its field is selected).
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
     * Validation of `first`/`skip` before `nodes`/`hasNextPage` query: [PagingValidation.QUERYDSL] (`first <= 0` and
     * a negative `skip` are errors, like the QueryDSL connections of the originals). Override it for another rule.
     */
    protected open val pagingValidation: PagingValidation get() = PagingValidation.QUERYDSL

    /** Override (calling `super`) to change the field's description. */
    @GraphQLDescription("The total amount of items in this connection")
    open suspend fun totalCount(): Int = query().totalCount()

    /** Override (calling `super`) to narrow the node type or change the field's description. */
    @GraphQLDescription("The resulting items.")
    open suspend fun nodes(): List<T> = query().nodes().map { it.toDTO() }

    /** Override (calling `super`) to change the field's description. */
    @GraphQLDescription("Whether this connection has a next page")
    open suspend fun hasNextPage(): Boolean = query().hasNextPage()

    /**
     * Additional condition depending on the authorized user (e.g. only own items for buyers), or null.
     */
    protected open fun authorizedUserFilter(): Condition? = null

    /** The statements of this connection (the condition is built per statement, after the paging validation). */
    private fun query(): ConnectionQuery<D> {
        val table = entityTemplate.converter.mappingContext.getRequiredPersistentEntity(entityClass.java).tableName
        val converter = entityTemplate.converter
        return ConnectionQuery(
            databaseClient = entityTemplate.databaseClient,
            table = entityTemplate.dataAccessStrategy.toSql(table),
            conditionOf = { Condition.allOf(predicate, filter?.toCondition(), authorizedUserFilter()) },
            orderBy = orderBy,
            first = first,
            skip = skip,
            join = join,
            primaryKey = primaryKey,
            pagingValidation = pagingValidation,
        ) { row, metadata -> converter.read(entityClass.java, row, metadata) }
    }
}
