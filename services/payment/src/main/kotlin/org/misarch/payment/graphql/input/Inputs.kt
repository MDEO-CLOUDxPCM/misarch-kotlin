package org.misarch.payment.graphql.input

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.generator.annotations.GraphQLName
import com.expediagroup.graphql.generator.annotations.GraphQLSkipInputSuffix
import org.misarch.payment.graphql.model.OrderDirection
import org.misarch.payment.graphql.model.PaymentInformationOrderField
import org.misarch.payment.graphql.model.PaymentMethod
import org.misarch.payment.graphql.model.PaymentOrderField
import org.misarch.payment.graphql.model.PaymentStatus
import org.misarch.payment.nest.JsDate
import org.misarch.common.nest.Uuid

@GraphQLDescription("Ordering options for payment informations")
@GraphQLName("PaymentInformationOrder")
@GraphQLSkipInputSuffix
data class PaymentInformationOrder(
    @property:GraphQLDescription("The field to order by")
    val field: PaymentInformationOrderField? = null,
    @property:GraphQLDescription("The direction to order by")
    val direction: OrderDirection? = null,
)

@GraphQLDescription("Filtering options for payment informations")
@GraphQLName("PaymentInformationFilter")
@GraphQLSkipInputSuffix
data class PaymentInformationFilter(
    @property:GraphQLDescription("Connected user id")
    val user: Uuid? = null,
    @property:GraphQLDescription("Linked payment method")
    val paymentMethod: PaymentMethod? = null,
)

@GraphQLDescription("Ordering options for payments")
@GraphQLName("PaymentOrder")
@GraphQLSkipInputSuffix
data class PaymentOrder(
    @property:GraphQLDescription("The field to order by")
    val field: PaymentOrderField? = null,
    @property:GraphQLDescription("The direction to order by")
    val direction: OrderDirection? = null,
)

@GraphQLDescription("Filtering options for payments")
@GraphQLName("PaymentFilter")
@GraphQLSkipInputSuffix
data class PaymentFilter(
    @property:GraphQLDescription("Current payment status")
    val status: PaymentStatus? = null,
    @property:GraphQLDescription("Payment Information ID")
    val paymentInformationId: String? = null,
    @property:GraphQLDescription("Payment method")
    val paymentMethod: PaymentMethod? = null,
    @property:GraphQLDescription("Timebox start for payment creation")
    val from: JsDate? = null,
    @property:GraphQLDescription("Timebox end for payment creation")
    val to: JsDate? = null,
)

@GraphQLDescription("All required informations to save an credit card")
data class CreateCreditCardInformationInput(
    @property:GraphQLDescription("The card holders name")
    val cardHolder: String,
    @property:GraphQLDescription("The credit cards number")
    val cardNumber: String,
    @property:GraphQLDescription("The credit cards expiration date")
    val expirationDate: String,
)
