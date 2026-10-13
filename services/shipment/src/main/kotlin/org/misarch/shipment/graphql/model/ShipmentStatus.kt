package org.misarch.shipment.graphql.model

import com.expediagroup.graphql.generator.annotations.GraphQLDescription

@GraphQLDescription("The status of a shipment.")
enum class ShipmentStatus {
    @GraphQLDescription("The shipment is pending.")
    PENDING,

    @GraphQLDescription("The shipment is in progress.")
    IN_PROGRESS,

    @GraphQLDescription("The shipment has been delivered.")
    DELIVERED,

    @GraphQLDescription("The shipment has failed.")
    FAILED
}