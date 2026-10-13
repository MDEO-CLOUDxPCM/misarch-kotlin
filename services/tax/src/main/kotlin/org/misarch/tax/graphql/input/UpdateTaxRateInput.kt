package org.misarch.tax.graphql.input

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import java.util.UUID

@GraphQLDescription("Input for the updateTaxRate mutation")
class UpdateTaxRateInput(
    @property:GraphQLDescription("The id of the TaxRate to update")
    val id: UUID,
    @property:GraphQLDescription("If provided, the new name of the TaxRate")
    val name: String?,
    @property:GraphQLDescription("If provided, the new description of the TaxRate")
    val description: String?
)
