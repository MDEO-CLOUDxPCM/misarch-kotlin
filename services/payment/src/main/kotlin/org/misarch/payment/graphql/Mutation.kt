package org.misarch.payment.graphql

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.server.operations.Mutation
import graphql.schema.DataFetchingEnvironment
import org.misarch.common.graphql.authorizedUser
import org.misarch.payment.graphql.input.CreateCreditCardInformationInput
import org.misarch.payment.graphql.model.Payment
import org.misarch.payment.graphql.model.PaymentInformation
import org.misarch.payment.service.PaymentInformationService
import org.misarch.payment.service.PaymentService
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Defines GraphQL mutations
 *
 * @property paymentInformationService service for payment informations
 * @property paymentService service for payments
 */
@Component
class Mutation(
    private val paymentInformationService: PaymentInformationService,
    private val paymentService: PaymentService,
) : Mutation {

    @GraphQLDescription("Adds a credit card to the users stored payment informations")
    suspend fun createCreditCardPaymentInformation(
        input: CreateCreditCardInformationInput,
        dfe: DataFetchingEnvironment
    ): PaymentInformation {
        val authorizedUser = dfe.authorizedUser
        authorizedUser.checkIsBuyer()
        return paymentInformationService.createCreditCardInformation(input, authorizedUser.id).toDTO()
    }

    @GraphQLDescription("Deletes a payment information by id")
    suspend fun deletePaymentInformation(
        @GraphQLDescription("UUID of payment information to delete")
        id: UUID,
        dfe: DataFetchingEnvironment
    ): PaymentInformation {
        return paymentInformationService.deletePaymentInformation(id, dfe.authorizedUser).toDTO()
    }

    @GraphQLDescription("Deletes a payment by id")
    suspend fun deletePayment(
        @GraphQLDescription("UUID of payment to delete")
        id: UUID,
        dfe: DataFetchingEnvironment
    ): Payment {
        dfe.authorizedUser.checkIsAdmin()
        return paymentService.deletePayment(id).toDTO()
    }

}
