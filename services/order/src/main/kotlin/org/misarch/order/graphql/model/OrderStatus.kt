package org.misarch.order.graphql.model

import com.expediagroup.graphql.generator.annotations.GraphQLDescription

@GraphQLDescription("Describes if order is placed, or yet pending. An order can be rejected during its lifetime.")
enum class OrderStatus {
    @GraphQLDescription("Order is saved a a template, this status can only last for max. 1 hour.")
    PENDING,

    @GraphQLDescription("Order is placed, which means SAGA for payment, fullfill and other validity checks need to be triggered.")
    PLACED,

    @GraphQLDescription("Something went wrong with the order and it was compensated in all relevant serivces.")
    REJECTED
}
