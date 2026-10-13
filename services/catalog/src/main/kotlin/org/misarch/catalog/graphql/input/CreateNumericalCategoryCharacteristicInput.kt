package org.misarch.catalog.graphql.input

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import java.util.UUID

@GraphQLDescription("Input for the createNumericalCategoryCharacteristic mutation")
class CreateNumericalCategoryCharacteristicInput(
    name: String,
    description: String,
    unit: String,
    @property:GraphQLDescription("The Category that the NumericalCategoryCharacteristic belongs to")
    val categoryId: UUID
) : NumericalCategoryCharacteristicInput(name, description, unit)