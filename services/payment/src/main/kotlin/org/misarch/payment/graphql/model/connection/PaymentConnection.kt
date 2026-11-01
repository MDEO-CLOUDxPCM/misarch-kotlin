package org.misarch.payment.graphql.model.connection

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.generator.annotations.GraphQLSkipInputSuffix
import org.misarch.common.graphql.connection.BaseFilter
import org.misarch.common.graphql.connection.BaseOrderField
import org.misarch.common.graphql.connection.connectionQuery
import org.misarch.common.persistence.Condition
import org.misarch.payment.graphql.model.Payment
import org.misarch.payment.graphql.model.PaymentMethod
import org.misarch.payment.graphql.model.PaymentStatus
import org.misarch.payment.persistence.model.PaymentEntity
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import java.time.OffsetDateTime
import java.util.UUID

/**
 * A GraphQL connection for [Payment]s (each field runs its own statement, only when selected).
 *
 * @param first The maximum number of items to return
 * @param skip The number of items to skip
 * @param filter The filter to apply to the items
 * @param predicate The predicate to filter the items by
 * @param order The order to sort the items by
 * @param entityTemplate executes the queries
 */
@GraphQLDescription("A connection of payments")
class PaymentConnection(
    first: Int?,
    skip: Int?,
    filter: PaymentFilter?,
    predicate: Condition?,
    order: PaymentOrder?,
    entityTemplate: R2dbcEntityTemplate,
) {

    private val query = entityTemplate.connectionQuery(
        PaymentEntity::class,
        Condition.allOf(predicate, filter?.toCondition()),
        (order ?: PaymentOrder.DEFAULT).toOrderBy(),
        first,
        skip
    )

    @GraphQLDescription("The resulting items")
    suspend fun nodes(): List<Payment>? {
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

@GraphQLDescription("The field to order Payments by")
enum class PaymentOrderField(override vararg val columns: String) : BaseOrderField {
    @GraphQLDescription("Order Payments by their ID")
    ID("id"),
}

@GraphQLDescription("Ordering options for payments")
@GraphQLSkipInputSuffix
class PaymentOrder(
    @property:GraphQLDescription("The field to order by")
    val field: PaymentOrderField?,
    @property:GraphQLDescription("The direction to order by")
    val direction: OrderDirection?
) {

    /** SQL order items; field and direction default to ID and ASC */
    fun toOrderBy(): List<String> {
        val direction = direction ?: OrderDirection.ASC
        return (field ?: PaymentOrderField.ID).columns.map { "$it ${direction.name}" }
    }

    companion object {
        val DEFAULT = PaymentOrder(PaymentOrderField.ID, OrderDirection.ASC)
    }
}

@GraphQLDescription("Filtering options for payments")
@GraphQLSkipInputSuffix
class PaymentFilter(
    @property:GraphQLDescription("Current payment status")
    val status: PaymentStatus?,
    @property:GraphQLDescription("Payment Information ID")
    val paymentInformationId: String?,
    @property:GraphQLDescription("Payment method")
    val paymentMethod: PaymentMethod?,
    @property:GraphQLDescription("Timebox start for payment creation")
    val from: OffsetDateTime?,
    @property:GraphQLDescription("Timebox end for payment creation")
    val to: OffsetDateTime?
) : BaseFilter {

    override fun toCondition(): Condition? {
        return Condition.allOf(
            status?.let { Condition.eq("status", it.name) },
            paymentInformationId?.let { id ->
                // a String in the schema: an id that is not a UUID matches no payment
                runCatching { UUID.fromString(id) }.getOrNull()?.let { Condition.eq("paymentInformationId", it) }
                    ?: Condition("FALSE")
            },
            paymentMethod?.let {
                Condition.raw(
                    "paymentInformationId IN (SELECT id FROM PaymentInformationEntity WHERE paymentMethod = ?)", it.name
                )
            },
            from?.let { Condition.ge("createdAt", it) },
            to?.let { Condition.le("createdAt", it) }
        )
    }

}
