package org.misarch.catalog.graphql.input

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import java.util.UUID

@GraphQLDescription("Input for creating a NumericalCategoryCharacteristicValue.")
class NumericalCategoryCharacteristicValueInput(
    @property:GraphQLDescription("The value of the NumericalCategoryCharacteristicValue.")
    val value: Double,
    @property:GraphQLDescription("The id of the CategoryCharacteristic.")
    val characteristicId: UUID
)