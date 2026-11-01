package org.misarch.catalog.graphql.model

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.generator.federation.directives.ShareableDirective
import java.util.UUID

@GraphQLDescription("A numerical value of a NumericalCategoryCharacteristic.")
@ShareableDirective
class NumericalCategoryCharacteristicValue(
    characteristicId: UUID,
    @property:GraphQLDescription("The value of the NumericalCategoryCharacteristic.")
    val value: Double,
) : CategoryCharacteristicValue(characteristicId)
