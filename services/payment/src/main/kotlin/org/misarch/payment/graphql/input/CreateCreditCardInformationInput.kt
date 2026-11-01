package org.misarch.payment.graphql.input

import com.expediagroup.graphql.generator.annotations.GraphQLDescription

@GraphQLDescription("All required informations to save an credit card")
class CreateCreditCardInformationInput(
    @property:GraphQLDescription("The card holders name")
    val cardHolder: String,
    @property:GraphQLDescription("The credit cards number")
    val cardNumber: String,
    @property:GraphQLDescription("The credit cards expiration date")
    val expirationDate: String
)
