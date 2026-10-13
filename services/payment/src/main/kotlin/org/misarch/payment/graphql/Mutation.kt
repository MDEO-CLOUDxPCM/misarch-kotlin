package org.misarch.payment.graphql

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.generator.annotations.GraphQLIgnore
import com.expediagroup.graphql.server.operations.Mutation
import graphql.schema.DataFetchingEnvironment
import org.misarch.payment.graphql.input.CreateCreditCardInformationInput
import org.misarch.payment.graphql.model.InformationSource
import org.misarch.payment.graphql.model.Payment
import org.misarch.payment.graphql.model.PaymentInformation
import org.misarch.common.nest.ClassValidator
import org.misarch.common.nest.Js
import org.misarch.common.nest.RolesGuard
import org.misarch.common.nest.Uuid
import org.misarch.payment.service.PaymentInformationService
import org.misarch.payment.service.PaymentService
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import java.time.Clock

/** Mutations of the original payment service (docs/specs/payment.md §4). No events are published. */
@Component
class Mutation(
    private val paymentInformationService: PaymentInformationService,
    private val paymentService: PaymentService,
    @GraphQLIgnore private val clock: Clock,
) : Mutation {

    private val mapper = JsonMapper.builder().build()

    @GraphQLDescription("Adds a credit card to the users stored payment informations")
    suspend fun createCreditCardPaymentInformation(
        input: CreateCreditCardInformationInput,
        dfe: DataFetchingEnvironment,
    ): PaymentInformation {
        val user = RolesGuard.check(dfe, RolesGuard.BUYER)
        @Suppress("UNCHECKED_CAST")
        val raw = dfe.arguments["input"] as Map<String, Any?>
        ClassValidator.validateOrThrow(CardValidation.schema(clock), mapper.valueToTree(raw))
        val userId = (if (user.isObject) user.get("id") else null)?.takeUnless { it.isNull }?.let { Js.toJsString(it) }
        val created = paymentInformationService.createCreditCardInformation(raw, userId)
        return PaymentInformation(InformationSource.Document(created))
    }

    @GraphQLDescription("Deletes a payment information by id")
    suspend fun deletePaymentInformation(
        @GraphQLDescription("UUID of payment information to delete")
        id: Uuid,
        dfe: DataFetchingEnvironment,
    ): PaymentInformation {
        val user = RolesGuard.check(dfe, RolesGuard.ADMIN, RolesGuard.EMPLOYEE, RolesGuard.BUYER)
        val deleted = paymentInformationService.delete(
            id.value,
            if (user.isObject) user.get("id") else null,
            if (user.isObject) user.get("roles") else null,
        )
        return PaymentInformation(InformationSource.Document(deleted))
    }

    @GraphQLDescription("Deletes a payment by id")
    suspend fun deletePayment(
        @GraphQLDescription("UUID of payment to delete")
        id: Uuid,
        dfe: DataFetchingEnvironment,
    ): Payment {
        RolesGuard.check(dfe, RolesGuard.ADMIN)
        return Payment(paymentService.delete(id.value))
    }
}
