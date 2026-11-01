package org.misarch.payment.graphql.model.connection

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.generator.annotations.GraphQLSkipInputSuffix
import org.misarch.common.graphql.connection.BaseFilter
import org.misarch.common.graphql.connection.BaseOrderField
import org.misarch.common.graphql.connection.NullableNodesConnection
import org.misarch.common.graphql.connection.Ordering
import org.misarch.common.persistence.Condition
import org.misarch.payment.graphql.model.Payment
import org.misarch.payment.graphql.model.PaymentMethod
import org.misarch.payment.graphql.model.PaymentStatus
import org.misarch.payment.persistence.model.PaymentEntity
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import java.time.OffsetDateTime
import java.util.UUID

/**
 * A GraphQL connection for [Payment]s.
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
) : NullableNodesConnection<Payment, PaymentEntity>(
    first,
    skip,
    filter,
    predicate,
    (order ?: PaymentOrder.DEFAULT).toOrderBy(PaymentOrderField.ID),
    entityTemplate,
    PaymentEntity::class,
    null,
) {

    @GraphQLDescription("The resulting items")
    override suspend fun nodes(): List<Payment>? = super.nodes()
}

@GraphQLDescription("The field to order Payments by")
enum class PaymentOrderField(override vararg val columns: String) : BaseOrderField {
    @GraphQLDescription("Order Payments by their ID")
    ID("id"),
}

@GraphQLDescription("Ordering options for payments")
@GraphQLSkipInputSuffix
class PaymentOrder(
    @property:GraphQLDescription("The direction to order by")
    override val direction: OrderDirection?,
    @property:GraphQLDescription("The field to order by")
    override val field: PaymentOrderField?
) : Ordering<PaymentOrderField> {

    companion object {
        val DEFAULT = PaymentOrder(OrderDirection.ASC, PaymentOrderField.ID)
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
