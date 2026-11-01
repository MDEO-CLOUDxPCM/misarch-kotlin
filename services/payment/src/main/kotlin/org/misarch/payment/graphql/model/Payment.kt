package org.misarch.payment.graphql.model

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.generator.federation.directives.FieldSet
import com.expediagroup.graphql.generator.federation.directives.KeyDirective
import graphql.schema.DataFetchingEnvironment
import org.misarch.common.graphql.load
import org.misarch.payment.graphql.dataloader.PaymentInformationDataLoader
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.CompletableFuture

@GraphQLDescription("A payment of an invoice or return")
@KeyDirective(fields = FieldSet("id"))
class Payment(
    @property:GraphQLDescription("The uuid identifier of the payment")
    val id: UUID,
    @property:GraphQLDescription("Payment Amount in the smallest currency unit (e.g. cents)")
    val totalAmount: Double,
    @property:GraphQLDescription("Status of the payment")
    val status: PaymentStatus,
    private val paymentInformationId: UUID,
    @property:GraphQLDescription("Date of the payment")
    val payedAt: OffsetDateTime?,
    @property:GraphQLDescription("Number of retries for the payment process")
    val numberOfRetries: Double
) {

    @GraphQLDescription("Used Payment Information")
    fun paymentInformation(dfe: DataFetchingEnvironment): CompletableFuture<PaymentInformation> {
        return dfe.load(PaymentInformationDataLoader::class, paymentInformationId)
    }

}
