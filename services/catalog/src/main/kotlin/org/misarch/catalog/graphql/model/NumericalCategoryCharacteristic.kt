package org.misarch.catalog.graphql.model

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.generator.federation.directives.FieldSet
import com.expediagroup.graphql.generator.federation.directives.KeyDirective
import java.util.*

@GraphQLDescription("A numerical characteristic of a Category.")
@KeyDirective(fields = FieldSet("id"))
class NumericalCategoryCharacteristic(
    id: UUID,
    name: String,
    description: String,
    categoryId: UUID,
    @property:GraphQLDescription("The unit of the NumericalCategoryCharacteristic.")
    val unit: String,
) : CategoryCharacteristic(id, name, description, categoryId)
