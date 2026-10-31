package org.misarch.payment.graphql.federation

import com.expediagroup.graphql.generator.federation.execution.FederatedTypeSuspendResolver
import graphql.schema.DataFetchingEnvironment
import org.misarch.payment.graphql.model.InformationSource
import org.misarch.payment.graphql.model.Payment
import org.misarch.payment.graphql.model.PaymentInformation
import org.misarch.payment.graphql.model.User
import org.misarch.common.nest.EntityErrors
import org.misarch.payment.service.PaymentInformationService
import org.misarch.payment.service.PaymentService
import org.springframework.stereotype.Component

/** `@ResolveReference` of `Payment` (public): `findById` (the information is resolved by its field). */
@Component
class PaymentResolver(private val paymentService: PaymentService) : FederatedTypeSuspendResolver<Payment> {
    override val typeName: String = "Payment"

    override suspend fun resolve(environment: DataFetchingEnvironment, representation: Map<String, Any>): Payment? =
        try {
            Payment(paymentService.findRowById(representation["id"].toString()))
        } catch (e: Throwable) {
            EntityErrors.record(environment, representation, e)
            null
        }
}

/** `@ResolveReference` of `PaymentInformation` (public). */
@Component
class PaymentInformationResolver(
    private val paymentInformationService: PaymentInformationService,
) : FederatedTypeSuspendResolver<PaymentInformation> {
    override val typeName: String = "PaymentInformation"

    override suspend fun resolve(environment: DataFetchingEnvironment, representation: Map<String, Any>): PaymentInformation? =
        try {
            PaymentInformation(InformationSource.Document(paymentInformationService.findById(representation["id"].toString())))
        } catch (e: Throwable) {
            EntityErrors.record(environment, representation, e)
            null
        }
}

/** `User` has no reference resolver in the original: the representation is the parent. */
@Component
class UserResolver : FederatedTypeSuspendResolver<User> {
    override val typeName: String = "User"

    override suspend fun resolve(environment: DataFetchingEnvironment, representation: Map<String, Any>): User =
        User(representation["id"]?.toString())
}
