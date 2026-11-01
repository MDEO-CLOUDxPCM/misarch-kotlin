package org.misarch.payment.graphql.model

import com.expediagroup.graphql.generator.annotations.GraphQLDescription

@GraphQLDescription("The supported payment methods")
enum class PaymentMethod {
    @GraphQLDescription("The user pays after ordering the product")
    INVOICE,

    @GraphQLDescription("The user pays before the product is shipped")
    PREPAYMENT,

    @GraphQLDescription("The amount is charged to the users credit card")
    CREDIT_CARD,
}
