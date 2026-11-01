package org.misarch.payment.persistence.model

import org.misarch.common.persistence.BaseEntity
import org.misarch.common.persistence.requiredId
import org.misarch.payment.graphql.model.PaymentInformation
import org.misarch.payment.graphql.model.PaymentMethod
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.util.UUID

/**
 * Entity for payment informations (the payment methods of a user)
 *
 * @property paymentMethod the payment method
 * @property publicMethodDetails details shown to the user (credit card: holder, masked number, expiration date)
 * @property secretMethodDetails details only used for the payment (credit card: the card data)
 * @property userId the user who owns the payment information
 * @property id unique identifier of the payment information
 */
@Table
class PaymentInformationEntity(
    val paymentMethod: PaymentMethod,
    val publicMethodDetails: CreditCardDetails?,
    val secretMethodDetails: CreditCardDetails?,
    val userId: UUID,
    @Id
    override val id: UUID?
) : BaseEntity<PaymentInformation> {

    override fun toDTO(): PaymentInformation {
        return PaymentInformation(
            id = requiredId,
            paymentMethod = paymentMethod,
            publicMethodDetails = publicMethodDetails?.toMap(),
            userId = userId
        )
    }

}
