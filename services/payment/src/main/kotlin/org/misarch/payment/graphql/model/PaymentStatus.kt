package org.misarch.payment.graphql.model

import com.expediagroup.graphql.generator.annotations.GraphQLDescription

@GraphQLDescription("The status of an payment of an invoice or return")
enum class PaymentStatus {
    @GraphQLDescription("The payment was created but not yet processed")
    OPEN,

    @GraphQLDescription("The payment is currently being processed")
    PENDING,

    @GraphQLDescription("The payment was successfully processed and the amount was transfered")
    SUCCEEDED,

    @GraphQLDescription("The payment processing failed indefinetely")
    FAILED,

    @GraphQLDescription("The payment was sold to external inkasso service")
    INKASSO,
}
