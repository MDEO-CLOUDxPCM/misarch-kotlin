package org.misarch.catalog.graphql.input

import com.expediagroup.graphql.generator.annotations.GraphQLDescription

@GraphQLDescription("Input to create a characteristic whose values have arithmetic meaning, i.e. '8GB'")
open class NumericalCategoryCharacteristicInput(
    @property:GraphQLDescription("The name of the NumericalCategoryCharacteristic")
    val name: String,
    @property:GraphQLDescription("The description of the NumericalCategoryCharacteristic")
    val description: String,
    @property:GraphQLDescription("The unit of the NumericalCategoryCharacteristic")
    val unit: String,
)