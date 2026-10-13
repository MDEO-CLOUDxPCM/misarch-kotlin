package org.misarch.catalog.graphql.model

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.generator.federation.directives.ShareableDirective
import java.util.*

@GraphQLDescription("A possible value for a categorical characteristic.")
@ShareableDirective
class CategoricalCategoryCharacteristicValue(
    characteristicId: UUID,
    @property:GraphQLDescription("The value of the characteristic.")
    val value: String,
) : CategoryCharacteristicValue(characteristicId)
