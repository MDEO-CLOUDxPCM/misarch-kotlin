package org.misarch.tax.graphql.input

import com.expediagroup.graphql.generator.annotations.GraphQLDescription

@GraphQLDescription("Input for creating a TaxRateVersion")
open class TaxRateVersionInput(
    @property:GraphQLDescription("The rate of the created TaxRateVersion")
    val rate: Double
)
