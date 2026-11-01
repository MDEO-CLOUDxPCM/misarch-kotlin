package org.misarch.payment.graphql.model.connection

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.generator.annotations.GraphQLSkipInputSuffix
import org.misarch.common.graphql.connection.BaseFilter
import org.misarch.common.graphql.connection.BaseOrderField
import org.misarch.common.graphql.connection.connectionQuery
import org.misarch.common.persistence.Condition
import org.misarch.payment.graphql.model.PaymentInformation
import org.misarch.payment.graphql.model.PaymentMethod
import org.misarch.payment.persistence.model.PaymentInformationEntity
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import java.util.UUID

/**
 * A GraphQL connection for [PaymentInformation]s (each field runs its own statement, only when selected).
 *
 * @param first The maximum number of items to return
 * @param skip The number of items to skip
 * @param filter The filter to apply to the items
 * @param predicate The predicate to filter the items by
 * @param order The order to sort the items by
 * @param entityTemplate executes the queries
 */
@GraphQLDescription("A connection of payment informations")
class PaymentInformationConnection(
    first: Int?,
    skip: Int?,
    filter: PaymentInformationFilter?,
    predicate: Condition?,
    order: PaymentInformationOrder?,
    entityTemplate: R2dbcEntityTemplate,
) {

    private val query = entityTemplate.connectionQuery(
        PaymentInformationEntity::class,
        Condition.allOf(predicate, filter?.toCondition()),
        (order ?: PaymentInformationOrder.DEFAULT).toOrderBy(),
        first,
        skip
    )

    @GraphQLDescription("The resulting items")
    suspend fun nodes(): List<PaymentInformation>? {
        return query.nodes().map { it.toDTO() }
    }

    @GraphQLDescription("The total amount of items in this connection")
    suspend fun totalCount(): Int {
        return query.totalCount()
    }

    @GraphQLDescription("Whether this connection has a next page")
    suspend fun hasNextPage(): Boolean {
        return query.hasNextPage()
    }

}

@GraphQLDescription("The field to order payment informations by")
enum class PaymentInformationOrderField(override vararg val columns: String) : BaseOrderField {
    @GraphQLDescription("Order payment informations by their id")
    ID("id"),
}

@GraphQLDescription("Ordering options for payment informations")
@GraphQLSkipInputSuffix
class PaymentInformationOrder(
    @property:GraphQLDescription("The field to order by")
    val field: PaymentInformationOrderField?,
    @property:GraphQLDescription("The direction to order by")
    val direction: OrderDirection?
) {

    /** SQL order items; field and direction default to ID and ASC */
    fun toOrderBy(): List<String> {
        val direction = direction ?: OrderDirection.ASC
        return (field ?: PaymentInformationOrderField.ID).columns.map { "$it ${direction.name}" }
    }

    companion object {
        val DEFAULT = PaymentInformationOrder(PaymentInformationOrderField.ID, OrderDirection.ASC)
    }
}

@GraphQLDescription("Filtering options for payment informations")
@GraphQLSkipInputSuffix
class PaymentInformationFilter(
    @property:GraphQLDescription("Connected user id")
    val user: UUID?,
    @property:GraphQLDescription("Linked payment method")
    val paymentMethod: PaymentMethod?
) : BaseFilter {

    override fun toCondition(): Condition? {
        return Condition.allOf(
            user?.let { Condition.eq("userId", it) },
            paymentMethod?.let { Condition.eq("paymentMethod", it.name) }
        )
    }

}
