package org.misarch.tax.graphql.input

import com.expediagroup.graphql.generator.annotations.GraphQLDescription

@GraphQLDescription("Input for the createTaxRate mutation")
class CreateTaxRateInput(
    @property:GraphQLDescription("The name of the created TaxRate")
    val name: String,
    @property:GraphQLDescription("The description of the created TaxRate")
    val description: String,
    @property:GraphQLDescription("The initial version of the created TaxRate")
    val initialVersion: TaxRateVersionInput
)
