package org.misarch.catalog.graphql.input

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import java.util.UUID

@GraphQLDescription("Input for the createCategoricalCategoryCharacteristic mutation")
class CreateCategoricalCategoryCharacteristicInput(
    name: String,
    description: String,
    @property:GraphQLDescription("The Category that the CategoricalCategoryCharacteristicI belongs to")
    val categoryId: UUID
) : CategoricalCategoryCharacteristicInput(name, description)