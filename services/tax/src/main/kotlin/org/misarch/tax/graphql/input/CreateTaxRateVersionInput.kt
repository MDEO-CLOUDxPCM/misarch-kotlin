package org.misarch.tax.graphql.input

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import java.util.UUID

@GraphQLDescription("Input for the createTaxRateVersion mutation")
class CreateTaxRateVersionInput(
    @property:GraphQLDescription("The id of the TaxRate the created TaxRateVersion belongs to")
    val taxRateId: UUID,
    rate: Double
) : TaxRateVersionInput(rate)
