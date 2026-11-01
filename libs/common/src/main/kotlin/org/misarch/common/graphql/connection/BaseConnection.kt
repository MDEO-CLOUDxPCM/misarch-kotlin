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
 * one table. Generated SQL:
 * ```
 * SELECT t.* FROM <table> t <join> WHERE <predicate AND filter AND authorizedUserFilter> ORDER BY <order>
 *   LIMIT <first> OFFSET <skip>
 * ```
 * (the statements of [connectionQuery], each runs only when its field is selected).
 * The main table has the alias `t`. [join] is appended verbatim (e.g.
 * `INNER JOIN ProductToCategoryEntity j ON j.productId = t.id`); conditions may then use `j.` columns.
 *
 * The field descriptions are the common ones; a connection type whose schema describes them differently overrides the
 * fields (calling `super`) with its own `@GraphQLDescription`. A schema with a nullable `nodes` field uses
 * [NullableNodesConnection] instead.
 *
 * @param T the GraphQL node type
 * @param D the entity type (table); a [BaseEntity] converts itself, other entities (e.g. link tables with a
 *   composite id) override [toDTO]
 * @property first the maximum number of items to return (null: all)
 * @property skip the number of items to skip
 * @property filter the filter input
 * @property predicate a fixed condition (e.g. `userId = <id>` for a user's addresses)
 * @property orderBy SQL order items (from [Ordering.toOrderBy])
 * @property entityTemplate executes the queries and maps rows to [entityClass]
 * @property entityClass the entity class (its table name comes from the R2DBC mapping context)
 * @property authorizedUser the authorized user (for [authorizedUserFilter])
 * @property join optional join clause
 */
@GraphQLIgnore
abstract class BaseConnection<T, D : Any>(
    first: Int?,
    skip: Int?,
    filter: BaseFilter?,
    predicate: Condition?,
    orderBy: List<String>,
    entityTemplate: R2dbcEntityTemplate,
    entityClass: KClass<D>,
    authorizedUser: AuthorizedUser?,
    join: String? = null,
) : AbstractConnection<T, D>(
    first, skip, filter, predicate, orderBy, entityTemplate, entityClass, authorizedUser, join
) {

    /** Override (calling `super`) to narrow the node type or change the field's description. */
    @GraphQLDescription("The resulting items.")
    open suspend fun nodes(): List<T> = page()
}

/**
 * A [BaseConnection] whose `nodes` field is nullable in the schema (`nodes: [T!]`); it is never null at runtime.
 */
@GraphQLIgnore
abstract class NullableNodesConnection<T, D : Any>(
    first: Int?,
    skip: Int?,
    filter: BaseFilter?,
    predicate: Condition?,
    orderBy: List<String>,
    entityTemplate: R2dbcEntityTemplate,
    entityClass: KClass<D>,
    authorizedUser: AuthorizedUser?,
    join: String? = null,
) : AbstractConnection<T, D>(
    first, skip, filter, predicate, orderBy, entityTemplate, entityClass, authorizedUser, join
) {

    /** Override (calling `super`) to change the field's description. */
    @GraphQLDescription("The resulting items.")
    open suspend fun nodes(): List<T>? = page()
}

/**
 * The part of [BaseConnection] and [NullableNodesConnection] without the `nodes` field: `totalCount`,
 * `hasNextPage` and the page query.
 */
@GraphQLIgnore
abstract class AbstractConnection<T, D : Any>(
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
     * Validation of `first`/`skip` before `nodes`/`hasNextPage` query: [PagingValidation.POSITIVE_LIMIT] (`first <= 0`
     * and a negative `skip` are errors). Override it for another rule.
     */
    protected open val pagingValidation: PagingValidation get() = PagingValidation.POSITIVE_LIMIT

    /** Override (calling `super`) to change the field's description. */
    @GraphQLDescription("The total amount of items in this connection")
    open suspend fun totalCount(): Int = query().totalCount()

    /** Override (calling `super`) to change the field's description. */
    @GraphQLDescription("Whether this connection has a next page")
    open suspend fun hasNextPage(): Boolean = query().hasNextPage()

    /** The nodes of the page. */
    protected suspend fun page(): List<T> = query().nodes().map { toDTO(it) }

    /** Converts an entity to its node; the default calls [BaseEntity.toDTO]. */
    @Suppress("UNCHECKED_CAST")
    protected open fun toDTO(entity: D): T = (entity as BaseEntity<T>).toDTO()

    /**
     * Additional condition depending on the authorized user (e.g. only own items for buyers), or null.
     */
    protected open fun authorizedUserFilter(): Condition? = null

    /** The statements of this connection (the condition is built per statement, after the paging validation). */
    private fun query(): ConnectionQuery<D> = entityTemplate.connectionQuery(
        entityClass,
        { Condition.allOf(predicate, filter?.toCondition(), authorizedUserFilter()) },
        orderBy, first, skip, join, primaryKey, pagingValidation
    )
}
