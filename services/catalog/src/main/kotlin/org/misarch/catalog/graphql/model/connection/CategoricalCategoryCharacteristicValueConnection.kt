package org.misarch.catalog.graphql.model.connection

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.generator.federation.directives.ShareableDirective
import org.misarch.catalog.graphql.model.CategoricalCategoryCharacteristicValue
import org.misarch.catalog.graphql.model.CategoryCharacteristic
import org.misarch.catalog.persistence.model.CategoryCharacteristicValueEntity
import org.misarch.common.graphql.connection.BaseOrder
import org.misarch.common.graphql.connection.BaseOrderField
import org.misarch.common.graphql.connection.ConnectionQuery
import org.misarch.common.graphql.connection.OrderDirection
import org.misarch.common.graphql.connection.connectionQuery
import org.misarch.common.persistence.Condition
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import java.util.UUID

/**
 * A GraphQL connection for [CategoryCharacteristic]s.
 * Lists the distinct (non-null) string values of the categorical characteristic values of one characteristic.
 *
 * @param first The maximum number of items to return
 * @param skip The number of items to skip
 * @param order The order to sort the items by
 * @param entityTemplate executes the queries
 * @param characteristicId The id of the characteristic to fetch the values for
 */
@GraphQLDescription("A connection to a list of `CategoricalCategoryCharacteristicValue` values.")
@ShareableDirective
class CategoricalCategoryCharacteristicValueConnection(
    private val first: Int?,
    private val skip: Int?,
    private val order: CategoricalCategoryCharacteristicValueOrder?,
    private val entityTemplate: R2dbcEntityTemplate,
    private val characteristicId: UUID
) {

    @GraphQLDescription("The total amount of items in this connection")
    suspend fun totalCount(): Int = query().totalCount()

    @GraphQLDescription("The resulting items.")
    suspend fun nodes(): List<CategoricalCategoryCharacteristicValue> =
        query().nodes().map { CategoricalCategoryCharacteristicValue(characteristicId, it) }

    @GraphQLDescription("Whether this connection has a next page")
    suspend fun hasNextPage(): Boolean = query().hasNextPage()

    private fun query(): ConnectionQuery<String> = entityTemplate.connectionQuery(
        CategoryCharacteristicValueEntity::class,
        Condition.isNotNull("stringValue") and Condition.eq("categoryCharacteristicId", characteristicId),
        (order ?: CategoricalCategoryCharacteristicValueOrder.DEFAULT)
            .toOrderBy(CategoricalCategoryCharacteristicValueOrderField.VALUE),
        first,
        skip
    ).distinctValues("stringValue", String::class)
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
