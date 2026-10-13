package org.misarch.catalog.graphql.model.connection

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.generator.federation.directives.ShareableDirective
import kotlinx.coroutines.reactor.awaitSingle
import org.misarch.catalog.graphql.model.CategoricalCategoryCharacteristicValue
import org.misarch.catalog.graphql.model.CategoryCharacteristic
import org.misarch.common.graphql.connection.BaseOrder
import org.misarch.common.graphql.connection.BaseOrderField
import org.misarch.common.graphql.connection.OrderDirection
import org.misarch.common.graphql.connection.PagingValidation
import org.springframework.r2dbc.core.DatabaseClient
import java.util.*

/**
 * A GraphQL connection for [CategoryCharacteristic]s.
 * Lists the distinct (non-null) string values of the categorical characteristic values of one characteristic
 * (the original used QueryDSL `select(stringValue).distinct()`, the SQL is the same).
 *
 * @param first The maximum number of items to return
 * @param skip The number of items to skip
 * @param order The order to sort the items by
 * @param databaseClient executes the queries
 * @param characteristicId The id of the characteristic to fetch the values for
 */
@GraphQLDescription("A connection to a list of `CategoricalCategoryCharacteristicValue` values.")
@ShareableDirective
class CategoricalCategoryCharacteristicValueConnection(
    private val first: Int?,
    private val skip: Int?,
    private val order: CategoricalCategoryCharacteristicValueOrder?,
    private val databaseClient: DatabaseClient,
    private val characteristicId: UUID
) {

    @GraphQLDescription("The total amount of items in this connection")
    suspend fun totalCount(): Int {
        return databaseClient.sql("SELECT COUNT(DISTINCT stringValue) FROM $TABLE WHERE $CONDITION")
            .bind("characteristicId", characteristicId)
            .map { row -> (row.get(0) as Number).toInt() }
            .one()
            .awaitSingle()
    }

    @GraphQLDescription("The resulting items.")
    suspend fun nodes(): List<CategoricalCategoryCharacteristicValue> {
        val orderBy = (order ?: CategoricalCategoryCharacteristicValueOrder.DEFAULT)
            .toOrderBy(CategoricalCategoryCharacteristicValueOrderField.VALUE)
            .joinToString(", ")
        // QueryDSL QueryModifiers of the original: `.offset(skip).limit(first)` validates both values
        PagingValidation.QUERYDSL.check((skip ?: 0).toLong(), first?.toLong())
        val limit = if (first != null) " LIMIT $first" else ""
        return databaseClient.sql(
            "SELECT DISTINCT stringValue FROM $TABLE WHERE $CONDITION ORDER BY $orderBy$limit OFFSET ${skip ?: 0}"
        )
            .bind("characteristicId", characteristicId)
            .map { row -> row.get(0, String::class.java)!! }
            .all()
            .collectList()
            .awaitSingle()
            .map { CategoricalCategoryCharacteristicValue(characteristicId, it) }
    }

    @GraphQLDescription("Whether this connection has a next page")
    suspend fun hasNextPage(): Boolean {
        if (first == null) {
            return false
        }
        PagingValidation.QUERYDSL.check(first.toLong() + (skip ?: 0), 1)
        return databaseClient.sql(
            "SELECT DISTINCT stringValue FROM $TABLE WHERE $CONDITION LIMIT 1 OFFSET ${first.toLong() + (skip ?: 0)}"
        )
            .bind("characteristicId", characteristicId)
            .map { _ -> true }
            .all()
            .hasElements()
            .awaitSingle()
    }

    companion object {
        private const val TABLE = "CategoryCharacteristicValueEntity"
        private const val CONDITION = "stringValue IS NOT NULL AND categoryCharacteristicId = :characteristicId"
    }
}

@GraphQLDescription("CategoricalCategoryCharacteristicValue order fields")
enum class CategoricalCategoryCharacteristicValueOrderField(
    override vararg val columns: String
) : BaseOrderField {
    @GraphQLDescription("Order CategoricalCategoryCharacteristicValue by their id")
    VALUE("stringValue")
}

@GraphQLDescription("CategoricalCategoryCharacteristicValue order")
class CategoricalCategoryCharacteristicValueOrder(
    direction: OrderDirection?, field: CategoricalCategoryCharacteristicValueOrderField?
) : BaseOrder<CategoricalCategoryCharacteristicValueOrderField>(direction, field) {

    companion object {
        val DEFAULT = CategoricalCategoryCharacteristicValueOrder(
            OrderDirection.ASC, CategoricalCategoryCharacteristicValueOrderField.VALUE
        )
    }
}
