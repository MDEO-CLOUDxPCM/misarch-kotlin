package org.misarch.catalog.graphql.input

import com.expediagroup.graphql.generator.annotations.GraphQLDescription

@GraphQLDescription("Input for the createCategory mutation")
class CreateCategoryInput(
    @property:GraphQLDescription("The name of the Category")
    val name: String,
    @property:GraphQLDescription("The description of the Category")
    val description: String,
    @property:GraphQLDescription("The CategoricalCategoryCharacteristics of the Category")
    val categoricalCharacteristics: List<CategoricalCategoryCharacteristicInput>,
    @property:GraphQLDescription("The NumericalCategoryCharacteristics of the Category")
    val numericalCharacteristics: List<NumericalCategoryCharacteristicInput>,
)