package org.misarch.inventory.graphql.model

import com.expediagroup.graphql.generator.annotations.GraphQLDescription

@GraphQLDescription("The status of an individual product item in the inventory")
enum class ProductItemStatus {
    @GraphQLDescription("The item is in storage, not yet sold or reserved")
    IN_STORAGE,

    @GraphQLDescription("The item has been reserved for a customer but not yet sold")
    RESERVED,

    @GraphQLDescription("The item is in the process of being fulfilled (packed, shipped)")
    IN_FULFILLMENT,

    @GraphQLDescription("The item has been shipped to the customer")
    SHIPPED,

    @GraphQLDescription("The item has been delivered to the customer")
    DELIVERED,

    @GraphQLDescription("The item has been returned by the customer")
    RETURNED,

    @GraphQLDescription("The item has been lost (e.g., during shipping or in the warehouse)")
    LOST,
}

@GraphQLDescription("The direction to order a list of items by")
enum class OrderDirection {
    @GraphQLDescription("Ascending Order")
    ASC,

    @GraphQLDescription("Decending Order")
    DESC,
}

@GraphQLDescription("The field to order Product Items by")
enum class ProductItemOrderField {
    @GraphQLDescription("Order Product Items by their ID")
    ID,
}
